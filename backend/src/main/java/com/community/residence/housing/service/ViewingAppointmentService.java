package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ReservationStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.dto.CreateViewingMessageDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.entity.ViewingMessage;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.mapper.ViewingMessageMapper;
import com.community.residence.housing.vo.AssigneeOptionVO;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.housing.vo.ViewingMessageVO;
import com.community.residence.housing.vo.ViewingPushVO;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.entity.ViolationRecord;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.service.TimeslotCoverage;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 看房预约业务逻辑：待确认→已预约→已完成 + 已取消/已违约（六大状态机 #5，
 * 复用 C7 冲突检测模式）。居民预约绑定 user_id；游客预约记录 visitor 信息。
 * R59：管理端分配带看人（assigned_staff_id）+ 预约居民与带看人会话消息
 * （WS 实时推送 + HTTP 轮询兜底，复制 R30 反馈会话模式）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewingAppointmentService {

    /** 占用时段的预约状态集合（冲突检测口径） */
    private static final List<String> OCCUPYING_STATUS = List.of("TO_CONFIRM", "RESERVED");

    /** 资源预约占用日程的状态集合（DEF-062 跨域日程冲突检查口径，C7 状态机常量） */
    private static final List<String> RESOURCE_OCCUPYING_STATUS =
            List.of(ReservationStatus.PENDING, ReservationStatus.RESERVED);

    /** R60 连续时长上限配置键（缺省 120 分钟） */
    private static final String CONFIG_MAX_CONTINUOUS = "viewing.max_continuous_minutes";

    /** R60 连续时长上限缺省值（sys_config 键未初始化时生效） */
    private static final int DEFAULT_MAX_CONTINUOUS_MINUTES = 120;

    private final ViewingAppointmentMapper appointmentMapper;
    private final ViewingMessageMapper messageMapper;
    private final HousingMapper housingMapper;
    private final HousingTimeslotMapper timeslotMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final SysConfigService sysConfigService;
    private final ViolationRecordMapper violationRecordMapper;
    private final ResourceReservationMapper resourceReservationMapper;
    private final RedissonClient redissonClient;
    private final com.community.residence.messaging.service.NotificationService notificationService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;

    /**
     * 创建预约：居民取令牌身份；游客必填姓名电话；时段落在房源模板内 + 冲突检测。
     * DEF-045：今日已结束时段（endTime<=now）拒绝（5804），与可约时段接口同口径。
     * R55 冲突校验复用 C7（R33）机制——「Redisson 分布式锁 + 数据库唯一约束
     * （V10 uk_viewing_slot）」双保险：锁内做区间重叠判定（与 C7 同口径，
     * 含半重叠/包含/被包含，仅首尾相接放行）后单条 INSERT 自动提交；
     * 完全同槽竞态漏网由唯一约束兜底。刻意不加 @Transactional（理由同 C7）。
     * DEF-062：锁内落库前增「预约人日程冲突」检查（跨房源 + 跨域）——同账号
     * 同日与任意看房预约或任意资源预约时间区间重叠即拒 5807（「影分身」拦截；
     * 游客预约无账号不参与）。
     */
    public ViewingAppointmentVO create(CreateViewingAppointmentDTO dto) {
        Housing housing = housingMapper.selectById(dto.getHousingId());
        if (housing == null || !"AVAILABLE".equals(housing.getStatus())) {
            throw new ResourceNotFoundException("房源不存在或不可预约");
        }
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束时间必须晚于开始时间");
        }

        /* R60 连续时长上限（sys_config viewing.max_continuous_minutes，缺省 120）：
           连续多段合并提交的超长请求 API 直调时由服务端兜底拒绝，消息含上限值 */
        int maxMinutes = readMaxContinuousMinutes();
        if (Duration.between(dto.getStartTime(), dto.getEndTime()).toMinutes() > maxMinutes) {
            throw new BusinessException(ErrorCode.VIEWING_DURATION_LIMIT,
                    "单次看房预约最长 " + maxMinutes + " 分钟");
        }

        /* DEF-045：今日已结束的时段不可预约（服务端权威，与 availableSlots 过滤同口径；
           endTime<=now 即视为过去时段，预约日期非今天的时段不受影响） */
        if (dto.getAppointmentDate().isEqual(LocalDate.now())
                && !dto.getEndTime().isAfter(LocalTime.now())) {
            throw new BusinessException(ErrorCode.PAST_SLOT);
        }
        /* R60 区间覆盖：所选时段须被当日可约模板段的并集完整覆盖（相邻模板
           可跨段连续，段间有间隙拒绝）——原单模板覆盖在连续多段合并提交场景
           会误拒，升级为并集口径，与 available-slots（BE-ISSUE-5 修复后）一致 */
        if (!isCoveredByTemplate(dto.getHousingId(), dto.getAppointmentDate(),
                dto.getStartTime(), dto.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所选时间不在房源可预约时段内");
        }

        /* 预约人身份先行解析（DEF-062 日程冲突检查需要）：居民取令牌身份；
           游客无账号（userId=null）不参与日程冲突检查 */
        Long userId = SecurityUtils.getUser() != null && SecurityUtils.hasRole(RoleConstants.RESIDENT)
                ? SecurityUtils.getUserId() : null;

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

            /* DEF-062 预约人日程冲突（跨房源 + 跨域，锁内落库前）：同账号同日与
               任意房源的看房预约（TO_CONFIRM/RESERVED）或任意资源的资源预约
               （PENDING/RESERVED）时间区间重叠（start<other.end 且 end>other.start，
               仅首尾相接放行）即拒绝；本条记录尚未落库天然不参与比对；
               游客预约（userId=null）跳过。同房源重叠已由上方冲突检测先行拦截，
               此处命中即跨房源/跨域安排 */
            if (userId != null) {
                Long crossHousing = appointmentMapper.selectCount(
                        new LambdaQueryWrapper<ViewingAppointment>()
                                .eq(ViewingAppointment::getUserId, userId)
                                .eq(ViewingAppointment::getAppointmentDate, dto.getAppointmentDate())
                                .lt(ViewingAppointment::getStartTime, dto.getEndTime())
                                .gt(ViewingAppointment::getEndTime, dto.getStartTime())
                                .in(ViewingAppointment::getStatus, OCCUPYING_STATUS));
                Long crossDomain = resourceReservationMapper.selectCount(
                        new LambdaQueryWrapper<ResourceReservation>()
                                .eq(ResourceReservation::getUserId, userId)
                                .eq(ResourceReservation::getReserveDate, dto.getAppointmentDate())
                                .lt(ResourceReservation::getStartTime, dto.getEndTime())
                                .gt(ResourceReservation::getEndTime, dto.getStartTime())
                                .in(ResourceReservation::getStatus, RESOURCE_OCCUPYING_STATUS));
                if (crossHousing > 0 || crossDomain > 0) {
                    throw new BusinessException(ErrorCode.VIEWING_SCHEDULE_CONFLICT,
                            "该时间段您已有其他预约安排，请调整时间");
                }
            }

            ViewingAppointment appointment = new ViewingAppointment();
            if (userId != null) {
                appointment.setUserId(userId);
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

    /**
     * 预约列表（分页）：数据权限三层口径——RESIDENT 限本人（user_id）；
     * STAFF 限被分配的预约（assigned_staff_id，服务人员端「我的带看」工作台，
     * R59 v1.3——DataScope 拦截器仅对 ADMIN 注入 community_id，无法表达该口径，
     * 故在 Service 层显式分支）；ADMIN/SUPER_ADMIN 不限（ADMIN 由
     * DataScopeInterceptor 按 community_id 统一注入）。
     */
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
        } else if (SecurityUtils.hasRole(RoleConstants.STAFF)) {
            wrapper.eq(ViewingAppointment::getAssignedStaffId, SecurityUtils.getUserId());
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
        notifyApplicant(appointment, "看房预约已确认",
                "您 " + appointment.getAppointmentDate() + " 的看房预约已确认");
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
        notifyApplicant(appointment, "看房预约已完成",
                "您 " + appointment.getAppointmentDate() + " 的看房预约已完成");
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
        notifyApplicant(appointment, "看房预约已取消",
                "您 " + appointment.getAppointmentDate() + " 的看房预约已取消" + (reason != null ? "：" + reason : ""));
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
        notifyApplicant(appointment, "看房预约违约处置",
                "您 " + appointment.getAppointmentDate() + " 的看房预约被标记违约：" + reason);
    }

    /* R55「确认/取消均通知对方」（DEF-023）：预约人有账号时经通知中心触达；
       游客预约无账号，通知语义不适用（违约处置等动作的游客触达无通道，为 R55 口径内边界） */
    private void notifyApplicant(ViewingAppointment appointment, String title, String content) {
        if (appointment.getUserId() != null) {
            notificationService.create(appointment.getUserId(), appointment.getCommunityId(),
                    title, content, "RESERVATION", "VIEWING_APPOINTMENT", appointment.getId());
        }
    }

    /**
     * 分配带看人（R59）：目标账号须存在、启用、且角色为 STAFF/ADMIN
     * （禁分配 SUPER_ADMIN——平台管理不承担带看；RESIDENT 不在 sys_user 表，
     * 查无即拒）。重复分配允许覆盖（换带看人场景），不校验旧带看人。
     * 成功后通知双方：带看人 + 预约居民（游客预约无账号跳过居民侧通知）。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE",
            targetType = "VIEWING_APPOINTMENT", targetId = "#id",
            content = "'看房预约分配带看人：' + #assigneeId")
    public ViewingAppointmentVO assign(Long id, Long assigneeId) {
        ViewingAppointment appointment = requireAppointment(id);
        SecurityUtils.checkCommunityAccess(appointment.getCommunityId());
        SysUser assignee = sysUserMapper.selectById(assigneeId);
        if (assignee == null) {
            throw new ResourceNotFoundException("目标账号不存在");
        }
        boolean roleAssignable = RoleConstants.STAFF.equals(assignee.getRole())
                || RoleConstants.ADMIN.equals(assignee.getRole());
        if (!roleAssignable || !CommonStatus.ACTIVE.equals(assignee.getStatus())) {
            throw new BusinessException(ErrorCode.VIEWING_ASSIGNEE_INVALID,
                    "目标账号不可分配（仅启用状态的服务人员或社区管理员）");
        }
        /* R59 管辖校验：ADMIN 带看人须管辖房源所在社区（STAFF 无社区绑定概念），
           防跨社区分配导致会话参与者越权读取非管辖社区预约信息 */
        if (RoleConstants.ADMIN.equals(assignee.getRole())
                && !sysAdminCommunityMapper.exists(new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getAdminId, assigneeId)
                        .eq(SysAdminCommunity::getCommunityId, appointment.getCommunityId()))) {
            throw new BusinessException(ErrorCode.VIEWING_ASSIGNEE_INVALID,
                    "目标管理员未管辖该房源所在社区，不可分配");
        }
        appointment.setAssignedStaffId(assigneeId);
        appointmentMapper.updateById(appointment);

        notificationService.create(assigneeId, appointment.getCommunityId(),
                "您已被分配为看房带看人",
                "您已被分配为 " + appointment.getAppointmentDate() + " 看房预约的带看人，可与预约人沟通看房事宜",
                "RESERVATION", "VIEWING_APPOINTMENT", appointment.getId());
        notifyApplicant(appointment, "您的看房预约已安排带看人",
                "您 " + appointment.getAppointmentDate() + " 的看房预约已安排带看人，可与其沟通看房事宜");
        return toVO(appointment);
    }

    /**
     * 带看人候选（R59）：启用状态 STAFF 全量 + 管辖该社区的启用 ADMIN，
     * 最小暴露面仅 ID/姓名/角色；调用者 ADMIN 仅可查其绑定社区（复用 checkCommunityAccess）。
     */
    public List<AssigneeOptionVO> assignableAssignees(Long communityId) {
        SecurityUtils.checkCommunityAccess(communityId);
        List<SysUser> staff = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, RoleConstants.STAFF)
                .eq(SysUser::getStatus, CommonStatus.ACTIVE)
                .orderByAsc(SysUser::getId));
        Set<Long> adminIds = Set.copyOf(sysAdminCommunityMapper.selectList(
                        new LambdaQueryWrapper<SysAdminCommunity>()
                                .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList());
        List<SysUser> admins = adminIds.isEmpty() ? List.of()
                : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .eq(SysUser::getStatus, CommonStatus.ACTIVE)
                        .in(SysUser::getId, adminIds)
                        .orderByAsc(SysUser::getId));
        List<AssigneeOptionVO> options = new ArrayList<>(staff.size() + admins.size());
        staff.forEach(u -> options.add(AssigneeOptionVO.of(u.getId(), u.getRealName(), RoleConstants.STAFF)));
        admins.forEach(u -> options.add(AssigneeOptionVO.of(u.getId(), u.getRealName(), RoleConstants.ADMIN)));
        return options;
    }

    /**
     * 会话消息列表（R59）：时间正序（按 id 升序，自增单调）非分页。
     * 可读=预约居民、带看人、或该房源所在社区的 ADMIN/SUPER_ADMIN；其余 403。
     */
    public List<ViewingMessageVO> listMessages(Long appointmentId) {
        ViewingAppointment appointment = requireAppointment(appointmentId);
        checkMessageReadAccess(appointment);
        return messageMapper.selectList(new LambdaQueryWrapper<ViewingMessage>()
                        .eq(ViewingMessage::getAppointmentId, appointmentId)
                        .orderByAsc(ViewingMessage::getId))
                .stream()
                .map(m -> toMessageVO(m, appointment))
                .toList();
    }

    /**
     * 发送会话消息（R59）：可发=仅预约居民或带看人本人（管理员可读不可发，
     * 除非其本人即被分配为该预约的带看人）。落库后 WS 推送到
     * /topic/appointment/{id}（载荷 {type:'APPOINTMENT_MESSAGE', data:VO}，
     * 复制 R30 反馈会话模式：双方均离线不推送、推送失败不回滚，前端轮询兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ViewingMessageVO sendMessage(Long appointmentId, CreateViewingMessageDTO dto) {
        ViewingAppointment appointment = requireAppointment(appointmentId);
        checkMessageSendAccess(appointment);
        ViewingMessage message = new ViewingMessage();
        message.setAppointmentId(appointmentId);
        message.setSenderId(SecurityUtils.getUserId());
        message.setContent(dto.getContent());
        messageMapper.insert(message);

        ViewingMessageVO vo = toMessageVO(message, appointment);
        pushMessage(appointment, vo);
        return vo;
    }

    /* 消息可读口径（R59）：与 WS 订阅鉴权（AppointmentSubscriptionInterceptor）同口径 */
    private void checkMessageReadAccess(ViewingAppointment appointment) {
        Long userId = SecurityUtils.getUserId();
        if (SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            return;
        }
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && appointment.getUserId() != null
                && appointment.getUserId().equals(userId)) {
            return;
        }
        if (SecurityUtils.hasRole(RoleConstants.STAFF)
                && appointment.getAssignedStaffId() != null
                && appointment.getAssignedStaffId().equals(userId)) {
            return;
        }
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)
                && SecurityUtils.getCommunityIds().contains(appointment.getCommunityId())) {
            return;
        }
        throw new ForbiddenException("非会话参与者，无权查看看房沟通消息");
    }

    /* 消息可发口径（R59）：仅预约居民或带看人本人 */
    private void checkMessageSendAccess(ViewingAppointment appointment) {
        Long userId = SecurityUtils.getUserId();
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && appointment.getUserId() != null
                && appointment.getUserId().equals(userId)) {
            return;
        }
        if (appointment.getAssignedStaffId() != null
                && appointment.getAssignedStaffId().equals(userId)) {
            return;
        }
        throw new ForbiddenException("仅预约居民或带看人可发送消息");
    }

    /* R59 会话实时推送（复制 R30 反馈会话模式）：广播到 /topic/appointment/{id}
       （订阅鉴权保证仅参与者收到）；双方均离线不推送，推送失败不回滚——前端轮询兜底 */
    private void pushMessage(ViewingAppointment appointment, ViewingMessageVO vo) {
        boolean residentOnline = appointment.getUserId() != null
                && webSocketSessionService.isOnline(appointment.getUserId());
        boolean assigneeOnline = appointment.getAssignedStaffId() != null
                && webSocketSessionService.isOnline(appointment.getAssignedStaffId());
        if (residentOnline || assigneeOnline) {
            try {
                messagingTemplate.convertAndSend("/topic/appointment/" + appointment.getId(),
                        ViewingPushVO.message(vo));
            } catch (Exception e) {
                log.warn("看房会话 WebSocket 推送失败，由轮询兜底：appointmentId={}", appointment.getId(), e);
            }
        }
    }

    /* 发送人姓名：预约居民取 resident.real_name，带看人取 sys_user.real_name
       （sender_id 多态引用，按预约参与者身份判别） */
    private ViewingMessageVO toMessageVO(ViewingMessage message, ViewingAppointment appointment) {
        ViewingMessageVO vo = ViewingMessageVO.from(message);
        Long senderId = message.getSenderId();
        if (senderId != null && senderId.equals(appointment.getUserId())) {
            Resident resident = residentMapper.selectById(senderId);
            vo.setSenderName(resident != null ? resident.getRealName() : "");
        } else if (senderId != null) {
            SysUser user = sysUserMapper.selectById(senderId);
            vo.setSenderName(user != null ? user.getRealName() : "");
        }
        return vo;
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

    /** 当日可约模板段并集是否完整覆盖 [start, end]（R60 区间覆盖，TimeslotCoverage 口径） */
    private boolean isCoveredByTemplate(Long housingId, LocalDate date,
                                        LocalTime start, LocalTime end) {
        int dayOfWeek = date.getDayOfWeek().getValue();
        List<TimeslotCoverage.Segment> segments = timeslotMapper.selectList(
                        new LambdaQueryWrapper<HousingTimeslot>()
                                .eq(HousingTimeslot::getHousingId, housingId)
                                .eq(HousingTimeslot::getDayOfWeek, dayOfWeek)
                                .eq(HousingTimeslot::getIsAvailable, 1))
                .stream()
                .map(t -> new TimeslotCoverage.Segment(t.getStartTime(), t.getEndTime()))
                .toList();
        return TimeslotCoverage.isCovered(segments, start, end);
    }

    /* R60 连续时长上限读取：sys_config 键缺省/非法回落 120（C7 freezeIfExceeded 同模式） */
    private int readMaxContinuousMinutes() {
        String value = sysConfigService.getValue(CONFIG_MAX_CONTINUOUS);
        if (value == null) {
            return DEFAULT_MAX_CONTINUOUS_MINUTES;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_MAX_CONTINUOUS_MINUTES;
        } catch (NumberFormatException e) {
            log.warn("连续时长上限配置不合法，使用缺省 {}：key={}, value={}",
                    DEFAULT_MAX_CONTINUOUS_MINUTES, CONFIG_MAX_CONTINUOUS, value);
            return DEFAULT_MAX_CONTINUOUS_MINUTES;
        }
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
        if (appointment.getAssignedStaffId() != null) {
            SysUser assignee = sysUserMapper.selectById(appointment.getAssignedStaffId());
            vo.setAssigneeName(assignee != null ? assignee.getRealName() : "");
        }
        return vo;
    }
}
