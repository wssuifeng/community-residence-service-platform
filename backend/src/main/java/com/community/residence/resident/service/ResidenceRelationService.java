package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.dto.MoveOutDTO;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.RelationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 居住关系业务逻辑：台账查询与迁出登记（迁出联动房屋状态回翻） */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResidenceRelationService {
    private final ResidenceDependencyService residenceDependencyService;

    private final ResidenceRelationMapper relationMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final com.community.residence.lease.service.LeaseService leaseService;
    private final com.community.residence.resident.mapper.ResidenceApplicationMapper applicationMapper;

    /** 居民居住关系列表：RESIDENT 限本人，ADMIN 限绑定社区（业务层过滤） */
    public PageVO<RelationVO> pageByResident(Long residentId, long page, long size, String status) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !residentId.equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人居住关系");
        }
        LambdaQueryWrapper<ResidenceRelation> wrapper = new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getResidentId, residentId)
                .orderByDesc(ResidenceRelation::getId);
        if ("ACTIVE".equals(status)) {
            wrapper.isNull(ResidenceRelation::getMoveOutDate);
        } else if ("MOVED_OUT".equals(status)) {
            wrapper.isNotNull(ResidenceRelation::getMoveOutDate);
        }
        /* 授权范围必须先进入SQL再分页；页后过滤会把总数错误改成当前页条数。 */
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            var bound = SecurityUtils.getCommunityIds();
            if (bound.isEmpty()) return PageVO.of(List.of(), 0, page, Math.min(size, 100));
            wrapper.in(ResidenceRelation::getCommunityId, bound);
        }
        Page<ResidenceRelation> result = relationMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /** 房屋居民列表（ADMIN） */
    public PageVO<RelationVO> pageByHouse(Long houseId, long page, long size, String status) {
        House house = houseMapper.selectById(houseId);
        if (house != null) {
            SecurityUtils.checkCommunityAccess(house.getCommunityId());
        }
        LambdaQueryWrapper<ResidenceRelation> wrapper = new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, houseId)
                .orderByDesc(ResidenceRelation::getId);
        if ("ACTIVE".equals(status)) {
            wrapper.isNull(ResidenceRelation::getMoveOutDate);
        } else if ("MOVED_OUT".equals(status)) {
            wrapper.isNotNull(ResidenceRelation::getMoveOutDate);
        }
        Page<ResidenceRelation> result = relationMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 办理迁出：仅在住关系可迁出；联动租约终止与房屋状态回翻 */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "RESIDENCE_RELATION", targetId = "#id", communityId = "#result.communityId", content = "'办理居民迁出'")
    public ResidenceRelation moveOut(Long id, MoveOutDTO dto) {
        ResidenceRelation relation = requireRelation(id);
        SecurityUtils.checkCommunityAccess(relation.getCommunityId());
        if (relation.getMoveOutDate() != null) {
            throw new BusinessException(ErrorCode.RELATION_NOT_ACTIVE);
        }
        houseMapper.selectByIdForUpdate(relation.getHouseId());
        relation = requireRelation(id);
        if (relation.getMoveOutDate() != null) throw new BusinessException(ErrorCode.RELATION_NOT_ACTIVE);
        /* 精确关联本租约；旧未关联关系只有唯一候选时兼容，不能批量终止多条租约。 */
        List<LeaseRecord> activeLeases = leaseRecordMapper.selectList(
                new LambdaQueryWrapper<LeaseRecord>()
                        .eq(LeaseRecord::getHouseId, relation.getHouseId())
                        .eq(LeaseRecord::getTenantId, relation.getResidentId())
                        .eq(relation.getLeaseId() != null, LeaseRecord::getId, relation.getLeaseId())
                        .eq(LeaseRecord::getStatus, "ACTIVE"));
        if (relation.getLeaseId() == null && activeLeases.size() > 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "历史居住关系无法唯一关联租约，请核对具体关系");
        }
        if (dto.getMoveOutDate() == null || relation.getMoveInDate() != null && dto.getMoveOutDate().isBefore(relation.getMoveInDate()))
            throw new BusinessException(ErrorCode.INVALID_PARAM, "迁出日期不能早于入住日期");
        var endedMembers = residenceDependencyService.endMembers(relation.getId(), dto.getMoveOutDate(), Boolean.TRUE.equals(dto.getConfirmDependents()));
        relation.setMoveOutDate(dto.getMoveOutDate());
        relationMapper.updateById(relation);
        activeLeases.forEach(lease -> leaseService.applyTransition(lease, "MOVED_OUT", "居住关系迁出登记"));

        /* 房屋无其他在住居民时回翻为空置 */
        Long livingCount = relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, relation.getHouseId())
                .isNull(ResidenceRelation::getMoveOutDate));
        if (livingCount == 0) {
            House house = houseMapper.selectById(relation.getHouseId());
            if (house != null) {
                house.setStatus(leaseRecordMapper.selectBlockingIdsForUpdate(relation.getHouseId()).isEmpty()
                        ? HouseStatusConstant.VACANT : HouseStatusConstant.RESERVED);
                houseMapper.updateById(house);
            }
        }
        log.info("居民已迁出：relationId={}, residentId={}, houseId={}, operator={}",
                id, relation.getResidentId(), relation.getHouseId(), SecurityUtils.getUserId());
        return relation;
    }

    public ResidenceRelation requireRelation(Long id) {
        ResidenceRelation relation = relationMapper.selectById(id);
        if (relation == null) {
            throw new ResourceNotFoundException("居住关系不存在");
        }
        return relation;
    }

    /**
     * 管理员直建居住关系（D-端点3，50 阶段第三批）：ADMIN 限绑定社区，直接建立
     * 关系不走入住审批流；校验居民存在且房屋可入住（VACANT，同审批通过口径），
     * 房屋状态置 OCCUPIED，写操作日志（R47 管理侧变更语义）。
     * 租约不联动建立——TENANT 租约仍走 C3 独立登记（与审批流 approve 口径一致）。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "RESIDENCE_RELATION", targetId = "#result.id", communityId = "#result.communityId", content = "'管理员直建居住关系：' + #dto.relationType + '，' + #dto.remark")
    public RelationVO adminCreate(com.community.residence.resident.dto.AdminCreateRelationDTO dto) {
        var resident = residentMapper.selectById(dto.getResidentId());
        if (resident == null) {
            throw new ResourceNotFoundException("居民不存在");
        }
        House house = houseMapper.selectByIdForUpdate(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        if (ResidenceDependencyService.isDependent(dto.getRelationType())) return registerDependent(dto, house);
        if (dto.getAnchorRelationId() != null) throw new BusinessException(ErrorCode.INVALID_PARAM,"主居住关系不能依赖另一条关系");
        if (!HouseStatusConstant.VACANT.equals(house.getStatus())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋非空置，不可直接登记居住关系");
        }
        /* 补录不得抢占正在审核/签约或生效租约的房屋，所有竞争路径共享房屋锁。 */
        if (applicationMapper.selectCount(new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceApplication>()
                .eq(com.community.residence.resident.entity.ResidenceApplication::getHouseId, house.getId())
                .eq(com.community.residence.resident.entity.ResidenceApplication::getStatus, "PENDING")) > 0
                || leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getHouseId, house.getId()).in(LeaseRecord::getStatus,
                        com.community.residence.common.constant.LeaseStatus.PENDING,
                        com.community.residence.common.constant.LeaseStatus.TO_SIGN,
                        com.community.residence.common.constant.LeaseStatus.ACTIVE)) > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "该房屋已有待处理申请或有效租约，请通过原业务链处理");
        }
        if (relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, house.getId())
                .isNull(ResidenceRelation::getMoveOutDate)) > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已有在住居民");
        }

        ResidenceRelation relation = new ResidenceRelation();
        relation.setResidentId(dto.getResidentId());
        relation.setCommunityId(house.getCommunityId());
        relation.setHouseId(house.getId());
        relation.setRelationType(dto.getRelationType());
        relation.setMoveInDate(dto.getMoveInDate());
        relation.setIsPrimary(1);
        relationMapper.insert(relation);

        house.setStatus(HouseStatusConstant.OCCUPIED);
        houseMapper.updateById(house);

        log.info("管理员直建居住关系：relationId={}, residentId={}, houseId={}, operator={}, remark={}",
                relation.getId(), dto.getResidentId(), dto.getHouseId(),
                SecurityUtils.getUserId(), dto.getRemark());
        return toVO(relation);
    }

    /* 同住登记必须有实际有效主关系；不以is_primary猜户主，不生成租约或支付单。 */
    private RelationVO registerDependent(com.community.residence.resident.dto.AdminCreateRelationDTO dto, House house) {
        if (!HouseStatusConstant.OCCUPIED.equals(house.getStatus())) throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT,"同住登记只能选择已入住且有有效主居住关系的房屋");
        var candidates = relationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId,house.getId()).isNull(ResidenceRelation::getMoveOutDate)
                .isNull(ResidenceRelation::getAnchorRelationId).in(ResidenceRelation::getRelationType,"OWNER","TENANT").last("FOR UPDATE"));
        var roots = dto.getAnchorRelationId()==null ? candidates : candidates.stream().filter(v->dto.getAnchorRelationId().equals(v.getId())).toList();
        if (roots.size()!=1) throw new BusinessException(ErrorCode.INVALID_PARAM,"必须明确选择本房屋唯一有效主居住关系；待签约/未付首期不能登记同住人员");
        ResidenceRelation root=roots.get(0);
        if (root.getResidentId().equals(dto.getResidentId())) throw new BusinessException(ErrorCode.DATA_EXISTS,"主住户本人不能重复登记为同住人员");
        if (dto.getMoveInDate()==null || root.getMoveInDate()!=null && dto.getMoveInDate().isBefore(root.getMoveInDate())) throw new BusinessException(ErrorCode.INVALID_PARAM,"同住入住日期不能早于主居住关系生效日期");
        if (root.getLeaseId()!=null) {
            LeaseRecord lease=leaseRecordMapper.selectById(root.getLeaseId());
            if(lease==null || !"ACTIVE".equals(lease.getStatus()) || !house.getId().equals(lease.getHouseId()) || !root.getResidentId().equals(lease.getTenantId()))
                throw new BusinessException(ErrorCode.INVALID_PARAM,"主租约未正式生效，不能登记同住人员");
        }
        var resident=residentMapper.selectById(dto.getResidentId());
        if (!"ACTIVE".equals(resident.getStatus())) throw new BusinessException(ErrorCode.INVALID_PARAM,"冻结账号不能新增居住关系");
        if (relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>().eq(ResidenceRelation::getHouseId,house.getId())
                .eq(ResidenceRelation::getResidentId,dto.getResidentId()).isNull(ResidenceRelation::getMoveOutDate))>0)
            throw new BusinessException(ErrorCode.DATA_EXISTS,"该居民已有本房屋有效居住关系");
        ResidenceRelation relation=new ResidenceRelation();
        relation.setResidentId(dto.getResidentId());relation.setCommunityId(house.getCommunityId());relation.setHouseId(house.getId());
        relation.setRelationType(dto.getRelationType());relation.setMoveInDate(dto.getMoveInDate());relation.setIsPrimary(1);
        relation.setAnchorRelationId(root.getId());relationMapper.insert(relation);
        return toVO(relation);
    }

    public java.util.List<RelationVO> dependents(Long relationId) {
        ResidenceRelation root=requireRelation(relationId);
        SecurityUtils.checkCommunityAccess(root.getCommunityId());
        return residenceDependencyService.activeMembers(relationId).stream().map(this::toVO).toList();
    }

    private RelationVO toVO(ResidenceRelation relation) {
        RelationVO vo = RelationVO.from(relation);
        var resident = residentMapper.selectById(relation.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
            vo.setResidentPhone(resident.getPhone());
        }
        House house = houseMapper.selectById(relation.getHouseId());
        if (house != null) {
            Unit unit = unitMapper.selectById(house.getUnitId());
            if (unit != null) {
                Building building = buildingMapper.selectById(unit.getBuildingId());
                String buildingName = building != null ? building.getName() : "";
                vo.setHouseLocation(buildingName + unit.getName() + house.getHouseNumber());
            }
        }
        return vo;
    }
}
