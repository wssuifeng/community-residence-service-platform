package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.constant.LeaseStatus;
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
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租住业务逻辑：待审核→待签约→已生效→已搬出→已归档 + 已驳回 状态机（架构设计 §6.2 六态）。
 * 「即将到期/已到期」为日期自动判定标注（expiryFlag），不是状态流转。
 * RESIDENT 数据权限在查询层显式按 tenant_id 约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseService {

    /** 状态机合法流转表 */
    /* 状态机（架构 §6.2 六态，R66 v1.8 增「待签约」）：待审核→待签约→已生效→已搬出→已归档；
       待签约亦可被驳回；DEF-013 删除 ACTIVE→ARCHIVED 跳级边（未搬出不可归档）；
       OCR DEF-115 删除 PENDING→ACTIVE 边——冻结状态机 v1.8 无此边（审核通过 ≠ 租约生效，
       生效仅经「承租确认 + 协议双方确认」闭环），审核通过只落到 TO_SIGN */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING", Set.of("TO_SIGN", "REJECTED"),
            "TO_SIGN", Set.of("ACTIVE", "REJECTED"),
            "ACTIVE", Set.of("MOVED_OUT"),
            "MOVED_OUT", Set.of("ARCHIVED"),
            "ARCHIVED", Set.of(),
            "REJECTED", Set.of());

    /** 即将到期标注窗口（天） */
    private static final int EXPIRING_WINDOW_DAYS = 30;

    /** 管理方直接登记租约时需要阻止的重叠状态。 */
    private static final Set<String> BLOCKING_CREATE_STATUSES =
            Set.of(LeaseStatus.PENDING, LeaseStatus.TO_SIGN, LeaseStatus.ACTIVE);

    private final LeaseRecordMapper leaseMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final com.community.residence.community.mapper.CommunityMapper communityMapper;
    private final LeaseChangeLogService changeLogService;
    private final NotificationService notificationService;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;

    /* 登记租约：房屋与租客须存在；初始 PENDING 待审核 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "LEASE_RECORD", targetId = "#result.id", content = "'创建租住记录'")
    public LeaseVO create(CreateLeaseDTO dto) {
        Resident tenant = residentMapper.selectById(dto.getResidentId());
        if (tenant == null) {
            throw new ResourceNotFoundException("租客不存在");
        }
        House house = lockHouse(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        if (!dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        Long conflict = leaseMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getTenantId, dto.getResidentId())
                .eq(LeaseRecord::getHouseId, dto.getHouseId())
                .in(LeaseRecord::getStatus, BLOCKING_CREATE_STATUSES));
        if (conflict > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该居民与房屋已有进行中的租约，无法重复登记");
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
        changeLogService.logCreate(lease, "管理方登记租约");
        return toVO(lease);
    }

    /**
     * 写入租约前锁定房屋行；旧单元测试未 stub 锁查询时回退到普通查询。
     */
    private House lockHouse(Long houseId) {
        House locked = houseMapper.selectByIdForUpdate(houseId);
        return locked != null ? locked : houseMapper.selectById(houseId);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_RECORD", targetId = "#id", content = "'更新租住记录'")
    public LeaseVO update(Long id, CreateLeaseDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        /* R16「归档后不可修改」（DEF-017）：流转端点已有状态机拦截，PUT 旁路补终态校验 */
        if ("ARCHIVED".equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已归档的租约不可修改");
        }
        if (!lease.getHouseId().equals(dto.getHouseId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "租约不允许变更房屋");
        }
        if (!dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        LeaseRecord snapshot = snapshot(lease);
        lease.setStartDate(dto.getStartDate());
        lease.setEndDate(dto.getEndDate());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        lease.setContractUrl(dto.getContractUrl());
        lease.setRemark(dto.getRemark());
        leaseMapper.updateById(lease);
        changeLogService.logAttributeChanges(snapshot, lease, "管理方编辑租约属性");
        return toVO(lease);
    }

    /**
     * 续租（需求 E3：止期顺延）：仅 ACTIVE 租约可续；新止期须晚于原止期；
     * 租金/押金/备注一并更新，状态保持 ACTIVE。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_RECORD", targetId = "#id", content = "'续租租住记录'")
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
        LeaseRecord snapshot = snapshot(lease);
        lease.setEndDate(dto.getNewEndDate());
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        if (StringUtils.hasText(dto.getRemark())) {
            lease.setRemark(dto.getRemark());
        }
        leaseMapper.updateById(lease);
        changeLogService.logRenew(snapshot, lease, "续租：" + dto.getNewEndDate());
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

    /* 状态流转：查表校验合法性；非法流转拒绝。TO_SIGN→ACTIVE 视为签约/管理方直接生效，
       须同步把房屋置为已入住（R66 v1.8：审核通过不再翻房态，生效动作才翻） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "LEASE_RECORD", targetId = "#id", content = "'租住状态变更为 ' + #dto.status")
    public void updateStatus(Long id, UpdateLeaseStatusDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        String current = lease.getStatus();
        String target = dto.getStatus();
        if (current.equals(target)) {
            return;
        }
        applyTransition(lease, target, dto.getRemark());
        log.info("租住状态流转：leaseId={}, {} -> {}, operator={}",
                id, current, target, SecurityUtils.getUserId());
    }

    /**
     * 生效流转内核（状态机校验 + 持久化 + 变更留痕 + 房态联动）：状态流转端点与
     * 协议双方确认（AgreementService）共用，避免两处各写一份状态机判定导致口径漂移。
     * 同状态重复调用直接返回（幂等）；目标为 ACTIVE 时把房屋置为已入住。
     * 持久化为条件更新（OCR DEF-115）：WHERE 携带流转前状态——两路并发确认只成功一路；
     * 0 行时重读，已为目标状态按幂等处理直接返回（不重复留痕/房态联动），否则拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyTransition(LeaseRecord lease, String target, String reason) {
        String current = lease.getStatus();
        if (current.equals(target)) {
            return;
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("租住状态不允许从 %s 流转到 %s", current, target));
        }
        lease.setStatus(target);
        if (StringUtils.hasText(reason)) {
            lease.setRemark(reason);
        }
        int rows = leaseMapper.update(lease, new LambdaUpdateWrapper<LeaseRecord>()
                .eq(LeaseRecord::getId, lease.getId())
                .eq(LeaseRecord::getStatus, current));
        if (rows == 0) {
            LeaseRecord fresh = leaseMapper.selectById(lease.getId());
            if (fresh != null && target.equals(fresh.getStatus())) {
                return;
            }
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("租住状态不允许从 %s 流转到 %s（并发变更，当前 %s）",
                            current, target, fresh == null ? "未知" : fresh.getStatus()));
        }
        changeLogService.logStatus(lease.getId(), current, target, reason);

        if (LeaseStatus.ACTIVE.equals(target)) {
            markHouseOccupied(lease.getHouseId());
        }
    }

    /* 租约生效同步房态（R66 v1.8）：房屋必须存在，缺失时 WARN 不阻断生效（历史数据可能已删房屋） */
    private void markHouseOccupied(Long houseId) {
        House house = houseMapper.selectById(houseId);
        if (house == null) {
            log.warn("租约生效但房屋不存在，跳过房态联动：houseId={}", houseId);
            return;
        }
        house.setStatus(HouseStatusConstant.OCCUPIED);
        houseMapper.updateById(house);
    }

    /**
     * 承租确认（R67）：**承约前置 = 本人 + 待签约状态**（用户 2026-09-22 裁决）——
     * 看房预约与租约群聊是**沟通手段**，不是承约门槛："管理端确认（审核通过→待签约）
     * + 居民端确认（承约）"即可直接推进协议确认。
     * **幂等且不覆盖首次留痕**（tenant_confirm_at/by 只写一次，首次确认时间才具证明意义）。
     * 确认后管理方方可发起租赁协议（AgreementService 前置校验），并通知本社区管理方。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_RECORD", targetId = "#id", content = "'居民确认承租'")
    public LeaseVO tenancyConfirm(Long id) {
        LeaseRecord lease = requireLease(id);
        Long residentId = SecurityUtils.getUserId();
        if (!lease.getTenantId().equals(residentId)) {
            throw new ForbiddenException("无权对他人租约确认承租");
        }
        if (!LeaseStatus.TO_SIGN.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,
                    "仅待签约租约可确认承租，当前状态：" + lease.getStatus());
        }
        if (lease.getTenantConfirmAt() == null) {
            lease.setTenantConfirmAt(LocalDateTime.now());
            lease.setTenantConfirmBy(residentId);
            leaseMapper.updateById(lease);
            changeLogService.logAgreement(lease.getId(), "居民确认承租",
                    "承租确认时间 " + lease.getTenantConfirmAt());
            notifyAdminsTenancyConfirmed(lease);
            log.info("居民确认承租：leaseId={}, residentId={}", id, residentId);
        }
        return toVO(lease);
    }

    /* 承租确认通知本社区管理方（口径同到期提醒：sys_admin_community 绑定的管理员账号） */
    private void notifyAdminsTenancyConfirmed(LeaseRecord lease) {
        for (Long adminId : boundAdminIds(lease.getCommunityId())) {
            notificationService.createForUser(adminId, lease.getCommunityId(),
                    "居民已确认承租",
                    "租约 #" + lease.getId() + "（房屋 " + lease.getHouseId() + "）居民已确认承租，可发起租赁协议",
                    "LEASE", "LEASE_TENANCY", lease.getId());
        }
    }

    /* 社区绑定管理员 ID 集合（通知口径同到期提醒任务） */
    private List<Long> boundAdminIds(Long communityId) {
        return sysAdminCommunityMapper.selectList(new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList();
    }

    /** 租约变更历史（字段级前后值）：RESIDENT 限本人，ADMIN 限绑定社区 */
    public java.util.List<com.community.residence.lease.vo.LeaseChangeVO> changes(Long id) {
        LeaseRecord lease = requireLease(id);
        checkReadAccess(lease);
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        }
        return changeLogService.list(id);
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

    /* 变更留痕用的字段快照：只拷贝参与 diff 的属性，避免误带主键参与比较 */
    private LeaseRecord snapshot(LeaseRecord lease) {
        LeaseRecord copy = new LeaseRecord();
        copy.setId(lease.getId());
        copy.setStartDate(lease.getStartDate());
        copy.setEndDate(lease.getEndDate());
        copy.setMonthlyRent(lease.getMonthlyRent());
        copy.setDeposit(lease.getDeposit());
        copy.setRemark(lease.getRemark());
        return copy;
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
            /* 社区名（居民端租约卡片按社区分组展示，R61 界面重设计补） */
            com.community.residence.community.entity.Community community =
                    communityMapper.selectById(house.getCommunityId());
            if (community != null) {
                vo.setCommunityName(community.getName());
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
