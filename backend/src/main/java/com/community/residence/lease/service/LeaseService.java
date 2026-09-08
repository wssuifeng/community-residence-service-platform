package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
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
import com.community.residence.lease.dto.CreateLeaseDTO;
import com.community.residence.lease.dto.RenewLeaseDTO;
import com.community.residence.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.vo.LeaseVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * 租住业务逻辑：待审核→已生效→已搬出→已归档 + 已驳回 状态机（架构设计 §6）。
 * 「即将到期/已到期」为日期自动判定标注（expiryFlag），不是状态流转。
 * RESIDENT 数据权限在查询层显式按 tenant_id 约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseService {

    /** 状态机合法流转表 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING", Set.of("ACTIVE", "REJECTED"),
            "ACTIVE", Set.of("MOVED_OUT", "ARCHIVED"),
            "MOVED_OUT", Set.of("ARCHIVED"),
            "ARCHIVED", Set.of(),
            "REJECTED", Set.of());

    /** 即将到期标注窗口（天） */
    private static final int EXPIRING_WINDOW_DAYS = 30;

    private final LeaseRecordMapper leaseMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;

    /* 登记租约：房屋与租客须存在；初始 PENDING 待审核 */
    @Transactional(rollbackFor = Exception.class)
    public LeaseVO create(CreateLeaseDTO dto) {
        Resident tenant = residentMapper.selectById(dto.getResidentId());
        if (tenant == null) {
            throw new ResourceNotFoundException("租客不存在");
        }
        House house = houseMapper.selectById(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        if (!dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }

        LeaseRecord lease = new LeaseRecord();
        lease.setTenantId(dto.getResidentId());
        lease.setCommunityId(house.getCommunityId());
        lease.setHouseId(dto.getHouseId());
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        lease.setContractUrl(dto.getContractUrl());
        lease.setRemark(dto.getRemark());
        lease.setStatus("PENDING");
        leaseMapper.insert(lease);
        return toVO(lease);
    }

    @Transactional(rollbackFor = Exception.class)
    public LeaseVO update(Long id, CreateLeaseDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        if (!lease.getHouseId().equals(dto.getHouseId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "租约不允许变更房屋");
        }
        if (!dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        lease.setContractUrl(dto.getContractUrl());
        lease.setRemark(dto.getRemark());
        leaseMapper.updateById(lease);
        return toVO(lease);
    }

    /**
     * 续租（需求 E3：止期顺延）：仅 ACTIVE 租约可续；新止期须晚于原止期；
     * 租金/押金/备注一并更新，状态保持 ACTIVE。
     */
    @Transactional(rollbackFor = Exception.class)
    public LeaseVO renew(Long id, RenewLeaseDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        if (!"ACTIVE".equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "仅已生效租约可续租，当前状态：" + lease.getStatus());
        }
        if (!dto.getNewEndDate().isAfter(lease.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "新结束日期必须晚于原结束日期");
        }
        lease.setEndDate(dto.getNewEndDate());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        if (StringUtils.hasText(dto.getRemark())) {
            lease.setRemark(dto.getRemark());
        }
        leaseMapper.updateById(lease);
        log.info("租约已续租：leaseId={}, newEndDate={}, operator={}",
                id, dto.getNewEndDate(), SecurityUtils.getUserId());
        return toVO(lease);
    }

    /** 租约详情：RESIDENT 限本人 */
    public LeaseVO getById(Long id) {
        LeaseRecord lease = requireLease(id);
        checkReadAccess(lease);
        return toVO(lease);
    }

    public PageVO<LeaseVO> page(long page, long size, String status, Long residentId, Long houseId) {
        LambdaQueryWrapper<LeaseRecord> wrapper = new LambdaQueryWrapper<LeaseRecord>()
                .eq(StringUtils.hasText(status), LeaseRecord::getStatus, status)
                .eq(residentId != null, LeaseRecord::getTenantId, residentId)
                .eq(houseId != null, LeaseRecord::getHouseId, houseId)
                .orderByDesc(LeaseRecord::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(LeaseRecord::getTenantId, SecurityUtils.getUserId());
        }
        Page<LeaseRecord> result = leaseMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 状态流转：查表校验合法性；非法流转拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, UpdateLeaseStatusDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        String current = lease.getStatus();
        String target = dto.getStatus();
        if (current.equals(target)) {
            return;
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("租住状态不允许从 %s 流转到 %s", current, target));
        }
        lease.setStatus(target);
        if (StringUtils.hasText(dto.getRemark())) {
            lease.setRemark(dto.getRemark());
        }
        leaseMapper.updateById(lease);
        log.info("租住状态流转：leaseId={}, {} -> {}, operator={}",
                id, current, target, SecurityUtils.getUserId());
    }

    /** 即将到期列表：ACTIVE 且结束日期在窗口内（到期提醒定时任务复用本查询语义） */
    public PageVO<LeaseVO> expiring(long page, long size, int days) {
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<LeaseRecord> wrapper = new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getStatus, "ACTIVE")
                .between(LeaseRecord::getEndDate, today, today.plusDays(days))
                .orderByAsc(LeaseRecord::getEndDate);
        Page<LeaseRecord> result = leaseMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    public LeaseRecord requireLease(Long id) {
        LeaseRecord lease = leaseMapper.selectById(id);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        return lease;
    }

    private void checkReadAccess(LeaseRecord lease) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !lease.getTenantId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人租住记录");
        }
    }

    private LeaseVO toVO(LeaseRecord lease) {
        LeaseVO vo = LeaseVO.from(lease);
        Resident tenant = residentMapper.selectById(lease.getTenantId());
        if (tenant != null) {
            vo.setTenantName(tenant.getRealName());
        }
        House house = houseMapper.selectById(lease.getHouseId());
        if (house != null) {
            Unit unit = unitMapper.selectById(house.getUnitId());
            if (unit != null) {
                Building building = buildingMapper.selectById(unit.getBuildingId());
                String buildingName = building != null ? building.getName() : "";
                vo.setHouseLocation(buildingName + unit.getName() + house.getHouseNumber());
            }
        }
        /* 到期标注（仅 ACTIVE 判定，架构设计 §6：非状态值） */
        if ("ACTIVE".equals(lease.getStatus())) {
            LocalDate today = LocalDate.now();
            if (lease.getEndDate().isBefore(today)) {
                vo.setExpiryFlag("EXPIRED");
            } else if (!lease.getEndDate().isAfter(today.plusDays(EXPIRING_WINDOW_DAYS))) {
                vo.setExpiryFlag("EXPIRING");
            }
        }
        return vo;
    }
}
