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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final ResourceReservationMapper reservationMapper;
    private final ViolationRecordMapper violationRecordMapper;
    private final PublicResourceMapper resourceMapper;
    private final ResourceTimeslotMapper timeslotMapper;
    private final ResidentMapper residentMapper;
    private final SysConfigService sysConfigService;

    /* 创建预约：校验时段落在模板内 + 冲突检测 + 容量校验 */
    @Transactional(rollbackFor = Exception.class)
    public ReservationVO create(CreateReservationDTO dto) {
        PublicResource resource = resourceMapper.selectById(dto.getResourceId());
        if (resource == null) {
            throw new ResourceNotFoundException("公共资源不存在");
        }
        if (dto.getEndTime().isBefore(dto.getStartTime())
                || dto.getEndTime().equals(dto.getStartTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束时间必须晚于开始时间");
        }

        /* 时段须完全落在该资源当日的某个可预约模板内 */
        ResourceTimeslot template = findCoveringTemplate(resource.getId(),
                dto.getReserveDate(), dto.getStartTime(), dto.getEndTime());
        if (template == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所选时间不在资源可预约时段内");
        }

        Long userId = SecurityUtils.getUserId();
        /* 同一居民同资源同日期不可重复预约在审/已预约记录 */
        Long mineCount = reservationMapper.selectCount(new LambdaQueryWrapper<ResourceReservation>()
                .eq(ResourceReservation::getUserId, userId)
                .eq(ResourceReservation::getResourceId, resource.getId())
                .eq(ResourceReservation::getReserveDate, dto.getReserveDate())
                .in(ResourceReservation::getStatus, OCCUPYING_STATUS));
        if (mineCount > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "同一天已预约该资源，不可重复预约");
        }

        checkCapacity(resource, dto.getReserveDate(), dto.getStartTime(), dto.getEndTime());

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
        reservationMapper.insert(reservation);
        return toVO(reservation);
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

    /* 确认预约：PENDING → RESERVED */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id, String remark) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.RESERVED);
        checkCapacity(resourceMapper.selectById(reservation.getResourceId()),
                reservation.getReserveDate(), reservation.getStartTime(), reservation.getEndTime());
        reservation.setStatus(ReservationStatus.RESERVED);
        reservationMapper.updateById(reservation);
        log.info("预约已确认：reservationId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /* 完成预约：RESERVED → COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id, String remark) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.COMPLETED);
        reservation.setStatus(ReservationStatus.COMPLETED);
        reservationMapper.updateById(reservation);
    }

    /* 拒绝预约：PENDING → REJECTED */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, String reason) {
        ResourceReservation reservation = requireReservation(id);
        SecurityUtils.checkCommunityAccess(reservation.getCommunityId());
        validateTransition(reservation, ReservationStatus.REJECTED);
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRemark(reason);
        reservationMapper.updateById(reservation);
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
    }

    /** 可预约时段：周循环模板按日期范围展开 + 当前占用计数 */
    public List<AvailableSlotVO> availableSlots(Long resourceId, LocalDate startDate, LocalDate endDate) {
        PublicResource resource = resourceMapper.selectById(resourceId);
        if (resource == null) {
            throw new ResourceNotFoundException("公共资源不存在");
        }
        LocalDate end = endDate != null ? endDate : startDate.plusDays(6);
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

        int capacity = resource.getCapacity() != null ? resource.getCapacity() : 1;
        List<AvailableSlotVO> slots = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate date = d;
            int dayOfWeek = date.getDayOfWeek().getValue();
            for (ResourceTimeslot template : templates) {
                if (template.getDayOfWeek() != dayOfWeek) {
                    continue;
                }
                int current = (int) occupying.stream()
                        .filter(r -> r.getReserveDate().equals(date)
                                && r.getStartTime().equals(template.getStartTime())
                                && r.getEndTime().equals(template.getEndTime()))
                        .count();
                slots.add(new AvailableSlotVO(template.getId(), date,
                        template.getStartTime(), template.getEndTime(),
                        capacity, current, current >= capacity ? "FULL" : "AVAILABLE"));
            }
        }
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

    /* 容量校验：同资源同日期同时间段的有效预约数 < 资源容量 */
    private void checkCapacity(PublicResource resource, LocalDate date,
                               LocalTime start, LocalTime end) {
        if (resource == null) {
            return;
        }
        int capacity = resource.getCapacity() != null ? resource.getCapacity() : 1;
        Long count = reservationMapper.selectCount(new LambdaQueryWrapper<ResourceReservation>()
                .eq(ResourceReservation::getResourceId, resource.getId())
                .eq(ResourceReservation::getReserveDate, date)
                .eq(ResourceReservation::getStartTime, start)
                .eq(ResourceReservation::getEndTime, end)
                .in(ResourceReservation::getStatus, OCCUPYING_STATUS));
        if (count >= capacity) {
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT, "该时段预约已满");
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
