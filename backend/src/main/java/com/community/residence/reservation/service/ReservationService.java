package com.community.residence.reservation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.entity.ViolationRecord;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.vo.AvailableSlotVO;
import com.community.residence.reservation.vo.ReservationVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.messaging.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 资源预约业务逻辑：待审核→已预约→已完成 + 已拒绝/已取消/已违约（六大状态机 #4）。
 * 冲突检测：同资源同日期时间段重叠 + 状态 PENDING/RESERVED；
 * 违约处置写 violation_record，违约次数超限自动冻结账号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    /** 占用时段的预约状态集合（冲突检测与容量统计口径） */
    private static final List<String> OCCUPYING_STATUS =
            List.of(ReservationStatus.PENDING, ReservationStatus.RESERVED);

    /** 违约冻结阈值配置键（缺省 3 次） */
    private static final String CONFIG_VIOLATION_MAX = "violation.max_count";

    /** 预约可提前的最大天数（today+N 当日为边界，DEF-053 业务规则） */
    private static final int MAX_ADVANCE_DAYS = 7;

    private final ResourceReservationMapper reservationMapper;
    private final ViolationRecordMapper violationRecordMapper;
    private final PublicResourceMapper resourceMapper;
    private final ResourceTimeslotMapper timeslotMapper;
    private final ResidentMapper residentMapper;
    private final SysConfigService sysConfigService;
    private final NotificationService notificationService;
    private final RedissonClient redissonClient;

    /**
     * 创建预约：Slot Grid 模型（08 §3.7 定案）。日期窗口（DEF-051/053）：
     * 仅可约今天起 7 天内，过去日期与今日已结束时段服务端拒绝（endTime<=now
     * 口径对齐 DEF-045 看房预约，进行中时段仍可约）。三道防线：
     * ① 栅格对齐校验（起止须为资源 slot_unit 整数倍，400）+ 模板覆盖校验；
     * ② Redisson 锁内对请求覆盖的每个栅格逐一检查 booked < capacity
     *    （同居民同资源同起始时段重复拦截 + 全局逐格容量检查）；
     * ③ V11 uk_reservation_user_slot 唯一约束兜底（同用户同槽并发重复）。
     * 并发防护沿用 DEF-005 锁模式：刻意不加 @Transactional——方法仅一条 INSERT，
     * 锁内自动提交保证锁释放前已落库行对后到并发请求可见。
     */
    public ReservationVO create(CreateReservationDTO dto) {
        PublicResource resource = resourceMapper.selectById(dto.getResourceId());
        if (resource == null) {
            throw new ResourceNotFoundException("公共资源不存在");
        }
        if (dto.getEndTime().isBefore(dto.getStartTime())
                || dto.getEndTime().equals(dto.getStartTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束时间必须晚于开始时间");
        }

        /* DEF-051/053：预约日期窗口服务端权威校验（前端日期控件仅挡常规入口，
           API 直调必须在此拦截）。判断顺序：过去日期 → 超出 7 天窗口
           （today+7 当日含边界内可约）→ 今日已结束时段（endTime<=now 即过去，
           与可约时段展示及看房预约 DEF-045 同口径） */
        LocalDate today = LocalDate.now();
        if (dto.getReserveDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.RESERVATION_PAST_SLOT,
                    "不能预约过去日期，请选择今天起 " + MAX_ADVANCE_DAYS + " 天内的日期");
        }
        if (dto.getReserveDate().isAfter(today.plusDays(MAX_ADVANCE_DAYS))) {
            throw new BusinessException(ErrorCode.RESERVATION_DATE_LIMIT,
                    "最多可提前 " + MAX_ADVANCE_DAYS + " 天预约");
        }
        if (dto.getReserveDate().isEqual(today) && !dto.getEndTime().isAfter(LocalTime.now())) {
            throw new BusinessException(ErrorCode.RESERVATION_PAST_SLOT, "不能预约已过去的时段");
        }

        /* 防线①a：起止须为资源栅格粒度整数倍（Slot Grid，08 §3.7） */
        int slotUnit = SlotGrids.slotUnitOf(resource);
        if (!SlotGrids.isAligned(dto.getStartTime(), slotUnit)
                || !SlotGrids.isAligned(dto.getEndTime(), slotUnit)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "预约起止时间必须为预约最小单位（" + slotUnit + " 分钟）的整数倍");
        }

        /* 防线①b：时段须完全落在该资源当日的某个可预约模板内 */
        ResourceTimeslot template = findCoveringTemplate(resource.getId(),
                dto.getReserveDate(), dto.getStartTime(), dto.getEndTime());
        if (template == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所选时间不在资源可预约时段内");
        }

        Long userId = SecurityUtils.getUserId();
        RLock lock = redissonClient.getLock(
                "reservation:lock:resource:" + resource.getId() + ":" + dto.getReserveDate());
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "预约请求繁忙，请稍后重试");
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "预约请求繁忙，请稍后重试");
            }
            /* 防线②a：同一居民同资源同起始时段不可重复预约（接口设计 §9.7.1.1 口径；
               DEF-002 并发穿透由 V11 uk_reservation_user_slot 兜底） */
            Long mineOverlap = reservationMapper.selectCount(new LambdaQueryWrapper<ResourceReservation>()
                    .eq(ResourceReservation::getUserId, userId)
                    .eq(ResourceReservation::getResourceId, resource.getId())
                    .eq(ResourceReservation::getReserveDate, dto.getReserveDate())
                    .lt(ResourceReservation::getStartTime, dto.getEndTime())
                    .gt(ResourceReservation::getEndTime, dto.getStartTime())
                    .in(ResourceReservation::getStatus, OCCUPYING_STATUS));
            if (mineOverlap > 0) {
                throw new BusinessException(ErrorCode.DATA_EXISTS,
                        "同一时段已有本人的预约，不可重复预约该时段");
            }

            /* 防线②b：锁内逐栅格容量检查——请求覆盖的每个栅格 booked < capacity
               （存量非对齐预约按覆盖即占用宽松计入） */
            int capacity = SlotGrids.capacityOf(resource);
            List<ResourceReservation> occupying = reservationMapper.selectList(
                    new LambdaQueryWrapper<ResourceReservation>()
                            .eq(ResourceReservation::getResourceId, resource.getId())
                            .eq(ResourceReservation::getReserveDate, dto.getReserveDate())
                            .in(ResourceReservation::getStatus, OCCUPYING_STATUS));
            for (LocalTime gridStart = dto.getStartTime();
                    gridStart.isBefore(dto.getEndTime()); gridStart = gridStart.plusMinutes(slotUnit)) {
                LocalTime slotStart = gridStart;
                LocalTime slotEnd = gridStart.plusMinutes(slotUnit);
                long booked = occupying.stream()
                        .filter(r -> r.getStartTime().isBefore(slotEnd)
                                && r.getEndTime().isAfter(slotStart))
                        .count();
                if (booked >= capacity) {
                    throw new BusinessException(ErrorCode.RESERVATION_CONFLICT,
                            "时段 " + slotStart + "~" + slotEnd + " 已约满");
                }
            }

            ResourceReservation reservation = new ResourceReservation();
            reservation.setUserId(userId);
            reservation.setResourceId(resource.getId());
            reservation.setCommunityId(resource.getCommunityId());
            reservation.setReserveDate(dto.getReserveDate());
            reservation.setStartTime(dto.getStartTime());
            reservation.setEndTime(dto.getEndTime());
            reservation.setStatus(ReservationStatus.PENDING);
            reservation.setPurpose(dto.getPurpose());
            reservation.setContactPhone(dto.getContactPhone());
            reservation.setRemark(dto.getRemark());
            try {
                reservationMapper.insert(reservation);
            } catch (DuplicateKeyException e) {
                /* 同用户同槽并发竞态被 V11 唯一约束拦截（锁失效时的最后防线） */
                throw new BusinessException(ErrorCode.RESERVATION_CONFLICT, "该时段已被预约");
            }
            return toVO(reservation);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
        }
    }

    public ReservationVO getById(Long id) {
        ResourceReservation reservation = requireReservation(id);
        checkReadAccess(reservation);
        return toVO(reservation);
    }

    public PageVO<ReservationVO> page(long page, long size, String status, Long resourceId,
                                      LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<ResourceReservation> wrapper = new LambdaQueryWrapper<ResourceReservation>()
                .eq(StringUtils.hasText(status), ResourceReservation::getStatus, status)
                .eq(resourceId != null, ResourceReservation::getResourceId, resourceId)
                .ge(startDate != null, ResourceReservation::getReserveDate, startDate)
                .le(endDate != null, ResourceReservation::getReserveDate, endDate)
                .orderByDesc(ResourceReservation::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(ResourceReservation::getUserId, SecurityUtils.getUserId());
        }
        Page<ResourceReservation> result = reservationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 确认预约：PENDING → RESERVED；容量校验排除自身（自身 PENDING 已计入占用） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "REVIEW", targetType = "RESOURCE_RESERVATION", targetId = "#id", content = "'预约审核通过'")
    public void confirm(Long id, String remark) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.RESERVED);
        checkCapacity(resourceMapper.selectById(reservation.getResourceId()),
                reservation.getReserveDate(), reservation.getStartTime(),
                reservation.getEndTime(), id);
        reservation.setStatus(ReservationStatus.RESERVED);
        reservationMapper.updateById(reservation);
        notificationService.create(reservation.getUserId(), reservation.getCommunityId(),
                "预约已确认", "您 " + reservation.getReserveDate() + " 的预约已确认",
                "RESERVATION", "RESOURCE_RESERVATION", id);
        log.info("预约已确认：reservationId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /* 完成预约：RESERVED → COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "RESOURCE_RESERVATION", targetId = "#id", content = "'预约完成登记'")
    public void complete(Long id, String remark) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.COMPLETED);
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservationMapper.updateById(reservation);
    }

    /* 拒绝预约：PENDING → REJECTED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "REVIEW", targetType = "RESOURCE_RESERVATION", targetId = "#id", content = "'预约审核拒绝：' + #reason")
    public void reject(Long id, String reason) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.REJECTED);
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRemark(reason);
        reservationMapper.updateById(reservation);
        notificationService.create(reservation.getUserId(), reservation.getCommunityId(),
                "预约未通过", "您 " + reservation.getReserveDate() + " 的预约未通过：" + reason,
                "RESERVATION", "RESOURCE_RESERVATION", id);
    }

    /* 取消预约：PENDING/RESERVED → CANCELLED；仅预约人本人可取消 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, String reason) {
        ResourceReservation reservation = requireReservation(id);
        if (!reservation.getUserId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅预约人本人可取消预约");
        }
        validateTransition(reservation, ReservationStatus.CANCELLED);
        reservation.setStatus(ReservationStatus.CANCELLED);
        reservation.setRemark(reason);
        reservationMapper.updateById(reservation);
    }

    /* 标记违约：RESERVED → VIOLATED；写违约记录，超限自动冻结 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DISPOSAL", targetType = "RESOURCE_RESERVATION", targetId = "#id", content = "'预约违约处置：' + #reason")
    public void violate(Long id, String reason) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.VIOLATED);
        reservation.setStatus(ReservationStatus.VIOLATED);
        reservation.setRemark(reason);
        reservationMapper.updateById(reservation);

        ViolationRecord record = new ViolationRecord();
        record.setUserId(reservation.getUserId());
        record.setViolationType("RESERVATION_NO_SHOW");
        record.setRelatedId(id);
        record.setPunishment("记录违约");
        record.setRemark(reason);
        violationRecordMapper.insert(record);

        freezeIfExceeded(reservation.getUserId(), reason);
        notificationService.create(reservation.getUserId(), reservation.getCommunityId(),
                "预约违约处置", "您 " + reservation.getReserveDate() + " 的预约被标记违约：" + reason,
                "RESERVATION", "RESOURCE_RESERVATION", id);
    }

    /**
     * 可预约时段（Slot Grid 栅格化，08 §3.7）：周循环模板按日期范围展开为
     * slot_unit 粒度栅格，一次范围查询该日该资源全部占用态预约后内存按栅格分桶
     * （「覆盖即占用」宽松计入，存量非对齐预约不清洗）。
     * 响应为字段兼容的标准化栅格数组（timeslotId 为「模板ID×10000+当日分钟数」
     * 的栅格标识，仅作前端选择键，非数据库主键）；同日按 startTime 排序。
     */
    public List<AvailableSlotVO> availableSlots(Long resourceId, LocalDate startDate, LocalDate endDate) {
        PublicResource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("公共资源不存在");
        }
        LocalDate end = endDate != null ? endDate : startDate.plusDays(6);
        int slotUnit = SlotGrids.slotUnitOf(resource);
        int capacity = SlotGrids.capacityOf(resource);
        List<ResourceTimeslot> templates = timeslotMapper.selectList(
                new LambdaQueryWrapper<ResourceTimeslot>()
                        .eq(ResourceTimeslot::getResourceId, resourceId)
                        .eq(ResourceTimeslot::getIsAvailable, 1));
        List<ResourceReservation> occupying = reservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(ResourceReservation::getResourceId, resourceId)
                        .in(ResourceReservation::getStatus, OCCUPYING_STATUS)
                        .ge(ResourceReservation::getReserveDate, startDate)
                        .le(ResourceReservation::getReserveDate, end));

        List<AvailableSlotVO> slots = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate date = d;
            int dayOfWeek = date.getDayOfWeek().getValue();
            List<ResourceReservation> dayOccupying = occupying.stream()
                    .filter(r -> r.getReserveDate().equals(date)).toList();
            for (ResourceTimeslot template : templates) {
                if (template.getDayOfWeek() != dayOfWeek) {
                    continue;
                }
                /* 栅格边界：模板起止向上对齐到 slot_unit 边界（存量非对齐模板宽容展示） */
                LocalTime gridStart = SlotGrids.alignUp(template.getStartTime(), slotUnit);
                LocalTime gridEnd = SlotGrids.alignUp(template.getEndTime(), slotUnit);
                for (LocalTime s = gridStart; s.isBefore(gridEnd); s = s.plusMinutes(slotUnit)) {
                    LocalTime slotStart = s;
                    LocalTime slotEnd = s.plusMinutes(slotUnit);
                    int booked = (int) dayOccupying.stream()
                            .filter(r -> r.getStartTime().isBefore(slotEnd)
                                    && r.getEndTime().isAfter(slotStart))
                            .count();
                    slots.add(new AvailableSlotVO(
                            SlotGrids.gridId(template.getId(), slotStart), date,
                            slotStart, slotEnd, capacity, booked,
                            booked >= capacity ? "FULL" : "AVAILABLE"));
                }
            }
        }
        /* 同日按开始时间稳定排序（原无 ORDER BY 的无序问题顺带修复） */
        slots.sort(java.util.Comparator.comparing(AvailableSlotVO::getDate)
                .thenComparing(AvailableSlotVO::getStartTime));
        return slots;
    }

    /** 居民违约记录列表（ADMIN） */
    public PageVO<Map<String, Object>> violations(Long residentId, long page, long size) {
        Page<ViolationRecord> result = violationRecordMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<ViolationRecord>()
                        .eq(ViolationRecord::getUserId, residentId)
                        .orderByDesc(ViolationRecord::getId));
        List<Map<String, Object>> records = result.getRecords().stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "residentId", r.getUserId(),
                        "violationType", r.getViolationType(),
                        "relatedId", r.getRelatedId(),
                        "punishment", r.getPunishment() != null ? r.getPunishment() : "",
                        "remark", r.getRemark() != null ? r.getRemark() : "",
                        "createdAt", r.getCreatedAt().toString()))
                .toList();
        return PageVO.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public ResourceReservation requireReservation(Long id) {
        ResourceReservation reservation = reservationMapper.selectById(id);
        if (reservation == null) {
            throw new ResourceNotFoundException("预约不存在");
        }
        return reservation;
    }

    private void validateTransition(ResourceReservation reservation, String target) {
        Map<String, Set<String>> allowed = Map.of(
                ReservationStatus.PENDING, Set.of(ReservationStatus.RESERVED,
                        ReservationStatus.REJECTED, ReservationStatus.CANCELLED),
                ReservationStatus.RESERVED, Set.of(ReservationStatus.COMPLETED,
                        ReservationStatus.CANCELLED, ReservationStatus.VIOLATED));
        Set<String> targets = allowed.getOrDefault(reservation.getStatus(), Set.of());
        if (!targets.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("预约状态不允许从 %s 流转到 %s", reservation.getStatus(), target));
        }
    }

    /** 找到完全覆盖所选时间段的当日模板（返回 null 表示时段非法） */
    private ResourceTimeslot findCoveringTemplate(Long resourceId, LocalDate date,
                                                  LocalTime start, LocalTime end) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        return timeslotMapper.selectList(new LambdaQueryWrapper<ResourceTimeslot>()
                        .eq(ResourceTimeslot::getResourceId, resourceId)
                        .eq(ResourceTimeslot::getDayOfWeek, dayOfWeek)
                        .eq(ResourceTimeslot::getIsAvailable, 1))
                .stream()
                .filter(t -> !start.isBefore(t.getStartTime()) && !end.isAfter(t.getEndTime()))
                .findFirst()
                .orElse(null);
    }

    /* 容量校验（confirm 用，排除自身）：Slot Grid 逐格口径——请求覆盖的每个
       slot_unit 栅格内占用数 < 资源容量；创建路径已在锁内做过同样检查，
       此处为审核流转时的防御性复核 */
    private void checkCapacity(PublicResource resource, LocalDate date,
                               LocalTime start, LocalTime end, Long excludeId) {
        if (resource == null) {
            return;
        }
        int slotUnit = SlotGrids.slotUnitOf(resource);
        int capacity = SlotGrids.capacityOf(resource);
        List<ResourceReservation> occupying = reservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(ResourceReservation::getResourceId, resource.getId())
                        .eq(ResourceReservation::getReserveDate, date)
                        .ne(excludeId != null, ResourceReservation::getId, excludeId)
                        .in(ResourceReservation::getStatus, OCCUPYING_STATUS));
        for (LocalTime s = start; s.isBefore(end); s = s.plusMinutes(slotUnit)) {
            LocalTime slotStart = s;
            LocalTime slotEnd = s.plusMinutes(slotUnit);
            long booked = occupying.stream()
                    .filter(r -> r.getStartTime().isBefore(slotEnd)
                            && r.getEndTime().isAfter(slotStart))
                    .count();
            if (booked >= capacity) {
                throw new BusinessException(ErrorCode.RESERVATION_CONFLICT, "该时段预约已满");
            }
        }
    }

    /* 违约超限自动冻结（violation.max_count 配置，缺省 3） */
    private void freezeIfExceeded(Long userId, String reason) {
        Long violationCount = violationRecordMapper.selectCount(
                new LambdaQueryWrapper<ViolationRecord>().eq(ViolationRecord::getUserId, userId));
        String maxConfig = sysConfigService.getValue(CONFIG_VIOLATION_MAX);
        int maxCount = 3;
        if (maxConfig != null) {
            try {
                maxCount = Integer.parseInt(maxConfig);
            } catch (NumberFormatException e) {
                log.warn("违约上限配置不合法，使用缺省 3：value={}", maxConfig);
            }
        }
        if (violationCount >= maxCount) {
            Resident resident = residentMapper.selectById(userId);
            if (resident != null) {
                resident.setStatus(CommonStatus.FROZEN);
                residentMapper.updateById(resident);
                log.warn("居民违约超限自动冻结：residentId={}, violations={}, threshold={}, lastReason={}",
                        userId, violationCount, maxCount, reason);
            }
        }
    }

    private void checkReadAccess(ResourceReservation reservation) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !reservation.getUserId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人预约");
        }
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("预约冲突锁释放异常（锁键已过期时正常）", e);
        }
    }

    private ReservationVO toVO(ResourceReservation reservation) {
        ReservationVO vo = ReservationVO.from(reservation);
        Resident user = residentMapper.selectById(reservation.getUserId());
        if (user != null) {
            vo.setUserName(user.getRealName());
        }
        PublicResource resource = resourceMapper.selectById(reservation.getResourceId());
        if (resource != null) {
            vo.setResourceName(resource.getName());
        }
        return vo;
    }
}
