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

    private final ResidenceRelationMapper relationMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final LeaseRecordMapper leaseRecordMapper;

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
        Page<ResidenceRelation> result = relationMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        PageVO<RelationVO> vo = PageVO.of(result.convert(this::toVO));
        /* ADMIN 数据级权限：过滤绑定社区（拦截器对 M:N 查询不注入） */
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            var bound = SecurityUtils.getCommunityIds();
            List<RelationVO> filtered = vo.getRecords().stream()
                    .filter(r -> bound.contains(r.getCommunityId())).toList();
            return PageVO.of(filtered, filtered.size(), page, size);
        }
        return vo;
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
    @Transactional(rollbackFor = Exception.class)
    public void moveOut(Long id, MoveOutDTO dto) {
        ResidenceRelation relation = requireRelation(id);
        SecurityUtils.checkCommunityAccess(relation.getCommunityId());
        if (relation.getMoveOutDate() != null) {
            throw new BusinessException(ErrorCode.RELATION_NOT_ACTIVE);
        }
        relation.setMoveOutDate(dto.getMoveOutDate());
        relationMapper.updateById(relation);

        /* 关联在住租约置为已迁出 */
        List<LeaseRecord> activeLeases = leaseRecordMapper.selectList(
                new LambdaQueryWrapper<LeaseRecord>()
                        .eq(LeaseRecord::getHouseId, relation.getHouseId())
                        .eq(LeaseRecord::getTenantId, relation.getResidentId())
                        .eq(LeaseRecord::getStatus, "ACTIVE"));
        activeLeases.forEach(lease -> {
            lease.setStatus("MOVED_OUT");
            leaseRecordMapper.updateById(lease);
        });

        /* 房屋无其他在住居民时回翻为空置 */
        Long livingCount = relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, relation.getHouseId())
                .isNull(ResidenceRelation::getMoveOutDate));
        if (livingCount == 0) {
            House house = houseMapper.selectById(relation.getHouseId());
            if (house != null) {
                house.setStatus(HouseStatusConstant.VACANT);
                houseMapper.updateById(house);
            }
        }
        log.info("居民已迁出：relationId={}, residentId={}, houseId={}, operator={}",
                id, relation.getResidentId(), relation.getHouseId(), SecurityUtils.getUserId());
    }

    public ResidenceRelation requireRelation(Long id) {
        ResidenceRelation relation = relationMapper.selectById(id);
        if (relation == null) {
            throw new ResourceNotFoundException("居住关系不存在");
        }
        return relation;
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
