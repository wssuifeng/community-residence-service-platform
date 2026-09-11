package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.reservation.entity.ViolationRecord;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 看房预约业务逻辑：待确认→已预约→已完成 + 已取消/已违约（六大状态机 #5，
 * 复用 C7 冲突检测模式）。居民预约绑定 user_id；游客预约记录 visitor 信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewingAppointmentService {

    /** 占用时段的预约状态集合（冲突检测口径） */
    private static final List<String> OCCUPYING_STATUS = List.of("TO_CONFIRM", "RESERVED");

    private final ViewingAppointmentMapper appointmentMapper;
    private final HousingMapper housingMapper;
    private final HousingTimeslotMapper timeslotMapper;
    private final ResidentMapper residentMapper;
    private final ViolationRecordMapper violationRecordMapper;
    private final RedissonClient redissonClient;

    /**
     * 创建预约：居民取令牌身份；游客必填姓名电话；时段落在房源模板内 + 冲突检测。
     * R55 冲突校验复用 C7（R33）机制——「Redisson 分布式锁 + 数据库唯一约束
     * （V10 uk_viewing_slot）」双保险：锁内做区间重叠判定（与 C7 同口径，
     * 含半重叠/包含/被包含，仅首尾相接放行）后单条 INSERT 自动提交；
     * 完全同槽竞态漏网由唯一约束兜底。刻意不加 @Transactional（理由同 C7）。
     */
    public ViewingAppointmentVO create(CreateViewingAppointmentDTO dto) {
        Housing housing = housingMapper.selectById(dto.getHousingId());
        if (housing == null || !"AVAILABLE".equals(housing.getStatus())) {
            throw new ResourceNotFoundException("房源不存在或不可预约");
        }
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束时间必须晚于开始时间");
        }
        HousingTimeslot template = findCoveringTemplate(dto.getHousingId(),
                dto.getAppointmentDate(), dto.getStartTime(), dto.getEndTime());
        if (template == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所选时间不在房源可预约时段内");
        }

        RLock lock = redissonClient.getLock(
                "viewing:lock:housing:" + dto.getHousingId() + ":" + dto.getAppointmentDate());
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

            /* 冲突检测：同房源同日期时间段重叠（任一有效状态）即拦截，与 C7 同口径 */
            Long conflictCount = appointmentMapper.selectCount(new LambdaQueryWrapper<ViewingAppointment>()
                    .eq(ViewingAppointment::getHousingId, dto.getHousingId())
                    .eq(ViewingAppointment::getAppointmentDate, dto.getAppointmentDate())
                    .lt(ViewingAppointment::getStartTime, dto.getEndTime())
                    .gt(ViewingAppointment::getEndTime, dto.getStartTime())
                    .in(ViewingAppointment::getStatus, OCCUPYING_STATUS));
            if (conflictCount > 0) {
                throw new BusinessException(ErrorCode.RESERVATION_CONFLICT, "所选时段与已有看房预约重叠");
            }

            ViewingAppointment appointment = new ViewingAppointment();
            if (SecurityUtils.getUser() != null
                    && SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
                appointment.setUserId(SecurityUtils.getUserId());
            } else {
                /* 游客预约：user_id 空 + visitor 信息必填 */
                if (!StringUtils.hasText(dto.getVisitorName())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "游客预约请填写姓名");
                }
                appointment.setVisitorName(dto.getVisitorName());
                appointment.setVisitorPhone(dto.getContactPhone());
            }
            appointment.setHousingId(housing.getId());
            appointment.setCommunityId(housing.getCommunityId());
            appointment.setAppointmentDate(dto.getAppointmentDate());
            appointment.setStartTime(dto.getStartTime());
            appointment.setEndTime(dto.getEndTime());
            appointment.setStatus("TO_CONFIRM");
            appointment.setContactPhone(dto.getContactPhone());
            appointment.setRemark(dto.getRemark());
            try {
                appointmentMapper.insert(appointment);
            } catch (DuplicateKeyException e) {
                /* 完全同槽并发竞态被 V10 唯一约束拦截（锁失效时的最后防线） */
                throw new BusinessException(ErrorCode.RESERVATION_CONFLICT, "该时段已被预约");
            }
            return toVO(appointment);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
        }
    }

    /** 预约详情：居民限本人；游客经公开路径查看（手机号核对由前端交互完成） */
    public ViewingAppointmentVO getById(Long id) {
        ViewingAppointment appointment = requireAppointment(id);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !appointment.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权查看他人预约");
        }
        return toVO(appointment);
    }

    public PageVO<ViewingAppointmentVO> page(long page, long size, String status, Long housingId,
                                            LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<ViewingAppointment> wrapper = new LambdaQueryWrapper<ViewingAppointment>()
                .eq(StringUtils.hasText(status), ViewingAppointment::getStatus, status)
                .eq(housingId != null, ViewingAppointment::getHousingId, housingId)
                .ge(startDate != null, ViewingAppointment::getAppointmentDate, startDate)
                .le(endDate != null, ViewingAppointment::getAppointmentDate, endDate)
                .orderByDesc(ViewingAppointment::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(ViewingAppointment::getUserId, SecurityUtils.getUserId());
        }
        Page<ViewingAppointment> result = appointmentMapper.selectPage(
                new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 确认预约：TO_CONFIRM → RESERVED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "REVIEW", targetType = "VIEWING_APPOINTMENT", targetId = "#id", content = "'看房预约确认'")
    public void confirm(Long id, String remark) {
        ViewingAppointment appointment = requireAppointment(id);
        SecurityUtils.checkCommunityAccess(appointment.getCommunityId());
        validateTransition(appointment, "RESERVED");
        appointment.setStatus("RESERVED");
        appointmentMapper.updateById(appointment);
    }

    /* 完成预约：RESERVED → COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "VIEWING_APPOINTMENT", targetId = "#id", content = "'看房预约完成'")
    public void complete(Long id, String remark) {
        ViewingAppointment appointment = requireAppointment(id);
        SecurityUtils.checkCommunityAccess(appointment.getCommunityId());
        validateTransition(appointment, "COMPLETED");
        appointment.setStatus("COMPLETED");
        appointmentMapper.updateById(appointment);
    }

    /* 取消预约：TO_CONFIRM/RESERVED → CANCELLED（居民本人或管理端） */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, String reason) {
        ViewingAppointment appointment = requireAppointment(id);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !appointment.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅预约人本人可取消");
        } else {
            SecurityUtils.checkCommunityAccess(appointment.getCommunityId());
        }
        validateTransition(appointment, "CANCELLED");
        appointment.setStatus("CANCELLED");
        appointment.setRemark(reason);
        appointmentMapper.updateById(appointment);
    }

    /* 标记违约：RESERVED → VIOLATED；居民违约写 violation_record（游客无账号不计） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DISPOSAL", targetType = "VIEWING_APPOINTMENT", targetId = "#id", content = "'看房预约违约处置：' + #reason")
    public void violate(Long id, String reason) {
        ViewingAppointment appointment = requireAppointment(id);
        SecurityUtils.checkCommunityAccess(appointment.getCommunityId());
        validateTransition(appointment, "VIOLATED");
        appointment.setStatus("VIOLATED");
        appointment.setRemark(reason);
        appointmentMapper.updateById(appointment);

        if (appointment.getUserId() != null) {
            ViolationRecord record = new ViolationRecord();
            record.setUserId(appointment.getUserId());
            record.setViolationType("VIEWING_NO_SHOW");
            record.setRelatedId(id);
            record.setPunishment("记录违约");
            record.setRemark(reason);
            violationRecordMapper.insert(record);
        }
    }

    public ViewingAppointment requireAppointment(Long id) {
        ViewingAppointment appointment = appointmentMapper.selectById(id);
        if (appointment == null) {
            throw new ResourceNotFoundException("看房预约不存在");
        }
        return appointment;
    }

    private void validateTransition(ViewingAppointment appointment, String target) {
        Map<String, Set<String>> allowed = Map.of(
                "TO_CONFIRM", Set.of("RESERVED", "CANCELLED"),
                "RESERVED", Set.of("COMPLETED", "CANCELLED", "VIOLATED"));
        Set<String> targets = allowed.getOrDefault(appointment.getStatus(), Set.of());
        if (!targets.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("看房预约状态不允许从 %s 流转到 %s", appointment.getStatus(), target));
        }
    }

    private HousingTimeslot findCoveringTemplate(Long housingId, LocalDate date,
                                                 LocalTime start, LocalTime end) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        return timeslotMapper.selectList(new LambdaQueryWrapper<HousingTimeslot>()
                        .eq(HousingTimeslot::getHousingId, housingId)
                        .eq(HousingTimeslot::getDayOfWeek, dayOfWeek)
                        .eq(HousingTimeslot::getIsAvailable, 1))
                .stream()
                .filter(t -> !start.isBefore(t.getStartTime()) && !end.isAfter(t.getEndTime()))
                .findFirst()
                .orElse(null);
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("看房冲突锁释放异常（锁键已过期时正常）", e);
        }
    }

    private ViewingAppointmentVO toVO(ViewingAppointment appointment) {
        ViewingAppointmentVO vo = ViewingAppointmentVO.from(appointment);
        if (appointment.getUserId() != null) {
            Resident resident = residentMapper.selectById(appointment.getUserId());
            if (resident != null) {
                vo.setVisitorName(resident.getRealName());
            }
        }
        Housing housing = housingMapper.selectById(appointment.getHousingId());
        if (housing != null) {
            vo.setHousingTitle(housing.getTitle());
        }
        return vo;
    }
}
