package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.CommonStatus;
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
import com.community.residence.community.service.CommunityService;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.dto.ApproveApplicationDTO;
import com.community.residence.resident.dto.CreateApplicationDTO;
import com.community.residence.resident.dto.RejectApplicationDTO;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.ApplicationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 入住申请业务逻辑：PENDING → APPROVED / REJECTED 状态机（终态不可变更）。
 * 审批通过自动建立居住关系、租住记录并翻转房屋状态为已入住（同一事务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResidenceApplicationService {

    private final ResidenceApplicationMapper applicationMapper;
    private final ResidenceRelationMapper relationMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final CommunityService communityService;

    /* 提交申请：房屋须空置；同一房屋同一居民不可重复在审 */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationVO create(CreateApplicationDTO dto) {
        House house = houseMapper.selectById(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        if (!HouseStatusConstant.VACANT.equals(house.getStatus())
                && !HouseStatusConstant.RESERVED.equals(house.getStatus())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT);
        }

        Long residentId = SecurityUtils.getUserId();
        Long pendingCount = applicationMapper.selectCount(new LambdaQueryWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getResidentId, residentId)
                .eq(ResidenceApplication::getHouseId, dto.getHouseId())
                .eq(ResidenceApplication::getStatus, "PENDING"));
        if (pendingCount > 0) {
            throw new BusinessException(ErrorCode.APPLICATION_PENDING_EXISTS);
        }

        ResidenceApplication application = new ResidenceApplication();
        application.setResidentId(residentId);
        application.setCommunityId(house.getCommunityId());
        application.setHouseId(house.getId());
        application.setRelationType(dto.getRelationType());
        application.setStatus("PENDING");
        application.setRemark(dto.getRemark());
        applicationMapper.insert(application);
        return toVO(application);
    }

    /** 申请详情：居民限本人，ADMIN 限绑定社区 */
    public ApplicationVO getById(Long id) {
        ResidenceApplication application = requireApplication(id);
        checkReadAccess(application);
        return toVO(application);
    }

    public PageVO<ApplicationVO> page(long page, long size, String status) {
        LambdaQueryWrapper<ResidenceApplication> wrapper = new LambdaQueryWrapper<ResidenceApplication>()
                .eq(StringUtils.hasText(status), ResidenceApplication::getStatus, status)
                .orderByDesc(ResidenceApplication::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(ResidenceApplication::getResidentId, SecurityUtils.getUserId());
        }
        Page<ResidenceApplication> result = applicationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 审批通过：建立居住关系 + 租住记录 + 房屋状态翻转，同一事务 */
    @Transactional(rollbackFor = Exception.class)
    public ApplicationVO approve(Long id, ApproveApplicationDTO dto) {
        ResidenceApplication application = requireApplication(id);
        checkReviewAccess(application);
        if (!"PENDING".equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED);
        }
        if (dto.getLeaseEndDate().isBefore(dto.getLeaseStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期不能早于开始日期");
        }
        House house = houseMapper.selectById(application.getHouseId());
        if (house == null || HouseStatusConstant.OCCUPIED.equals(house.getStatus())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已被入住，无法通过申请");
        }

        /* 居住关系：TENANT 建租住记录；OWNER/FAMILY 建关系即可（租约由 C3 独立登记） */
        ResidenceRelation relation = new ResidenceRelation();
        relation.setResidentId(application.getResidentId());
        relation.setCommunityId(application.getCommunityId());
        relation.setHouseId(application.getHouseId());
        relation.setRelationType(application.getRelationType());
        relation.setMoveInDate(dto.getLeaseStartDate());
        relation.setIsPrimary(1);
        relationMapper.insert(relation);

        if ("TENANT".equals(application.getRelationType())) {
            LeaseRecord lease = new LeaseRecord();
            lease.setTenantId(application.getResidentId());
            lease.setCommunityId(application.getCommunityId());
            lease.setHouseId(application.getHouseId());
            lease.setStartDate(dto.getLeaseStartDate());
            lease.setEndDate(dto.getLeaseEndDate());
            lease.setMonthlyRent(dto.getMonthlyRent());
            lease.setDeposit(dto.getDeposit());
            lease.setStatus("ACTIVE");
            lease.setRemark("入住申请审批通过自动建立");
            leaseRecordMapper.insert(lease);
        }

        house.setStatus(HouseStatusConstant.OCCUPIED);
        houseMapper.updateById(house);

        application.setStatus("APPROVED");
        application.setReviewerId(SecurityUtils.getUserId());
        application.setReviewTime(LocalDateTime.now());
        application.setReviewRemark(dto.getRemark());
        applicationMapper.updateById(application);
        log.info("入住申请已通过：applicationId={}, relationId={}, operator={}",
                id, relation.getId(), SecurityUtils.getUserId());
        return toVO(application);
    }

    /* 审批拒绝：终态不可变更 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, RejectApplicationDTO dto) {
        ResidenceApplication application = requireApplication(id);
        checkReviewAccess(application);
        if (!"PENDING".equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED);
        }
        application.setStatus("REJECTED");
        application.setReviewerId(SecurityUtils.getUserId());
        application.setReviewTime(LocalDateTime.now());
        application.setReviewRemark(dto.getReason());
        applicationMapper.updateById(application);
        log.info("入住申请已拒绝：applicationId={}, reason={}, operator={}",
                id, dto.getReason(), SecurityUtils.getUserId());
    }

    public ResidenceApplication requireApplication(Long id) {
        ResidenceApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new ResourceNotFoundException("入住申请不存在");
        }
        return application;
    }

    /* 读权限：RESIDENT 限本人；ADMIN 限绑定社区；STAFF 只读放行 */
    private void checkReadAccess(ResidenceApplication application) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !application.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人申请");
        }
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            SecurityUtils.checkCommunityAccess(application.getCommunityId());
        }
    }

    /* 审核权限：ADMIN 限绑定社区（SUPER_ADMIN 放行） */
    private void checkReviewAccess(ResidenceApplication application) {
        SecurityUtils.checkCommunityAccess(application.getCommunityId());
        communityService.requireCommunity(application.getCommunityId());
    }

    private ApplicationVO toVO(ResidenceApplication application) {
        ApplicationVO vo = ApplicationVO.from(application);
        Resident resident = residentMapper.selectById(application.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        House house = houseMapper.selectById(application.getHouseId());
        if (house != null) {
            vo.setHouseLocation(buildHouseLocation(house));
        }
        return vo;
    }

    /** 拼装「楼栋-单元-房号」位置描述 */
    private String buildHouseLocation(House house) {
        Unit unit = unitMapper.selectById(house.getUnitId());
        if (unit == null) {
            return house.getHouseNumber();
        }
        Building building = buildingMapper.selectById(unit.getBuildingId());
        String buildingName = building != null ? building.getName() : "";
        return buildingName + unit.getName() + house.getHouseNumber();
    }
}
