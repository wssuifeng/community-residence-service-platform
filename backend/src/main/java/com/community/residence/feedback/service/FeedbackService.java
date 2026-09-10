package com.community.residence.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.feedback.dto.CloseFeedbackDTO;
import com.community.residence.feedback.dto.CreateFeedbackDTO;
import com.community.residence.feedback.dto.SendMessageDTO;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.entity.FeedbackAttachment;
import com.community.residence.feedback.entity.FeedbackMessage;
import com.community.residence.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.feedback.mapper.FeedbackMapper;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.feedback.vo.AttachmentVO;
import com.community.residence.feedback.vo.FeedbackPushVO;
import com.community.residence.feedback.vo.FeedbackVO;
import com.community.residence.feedback.vo.MessageVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 反馈业务逻辑：待受理 → 会话中 → 已办结（六大状态机 #6）。
 * 管理员首次发消息即受理（PENDING → IN_SESSION）；办结后不可再发消息。
 * RESIDENT 数据权限在查询层显式按 resident_id 约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    /** 状态机：待受理 → 会话中 → 已办结（终态不可回退） */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING", Set.of("IN_SESSION"),
            "IN_SESSION", Set.of("CLOSED"),
            "CLOSED", Set.of());

    private final FeedbackMapper feedbackMapper;
    private final FeedbackMessageMapper messageMapper;
    private final FeedbackAttachmentMapper attachmentMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final FileUploadService fileUploadService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;

    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO create(CreateFeedbackDTO dto) {
        Feedback feedback = new Feedback();
        feedback.setResidentId(SecurityUtils.getUserId());
        feedback.setCommunityId(dto.getCommunityId());
        feedback.setTitle(dto.getTitle());
        feedback.setContent(dto.getContent());
        feedback.setCategory(dto.getCategory());
        feedback.setStatus("PENDING");
        feedbackMapper.insert(feedback);
        return toVO(feedback);
    }

    /** 反馈详情：RESIDENT 限本人 */
    public FeedbackVO getById(Long id) {
        Feedback feedback = requireFeedback(id);
        checkReadAccess(feedback);
        return toVO(feedback);
    }

    public PageVO<FeedbackVO> page(long page, long size, String status, String category) {
        LambdaQueryWrapper<Feedback> wrapper = new LambdaQueryWrapper<Feedback>()
                .eq(StringUtils.hasText(status), Feedback::getStatus, status)
                .eq(StringUtils.hasText(category), Feedback::getCategory, category)
                .orderByDesc(Feedback::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(Feedback::getResidentId, SecurityUtils.getUserId());
        }
        Page<Feedback> result = feedbackMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 办结：IN_SESSION → CLOSED；办结说明作为系统消息落档 */
    @Transactional(rollbackFor = Exception.class)
    public void close(Long id, CloseFeedbackDTO dto) {
        Feedback feedback = requireFeedback(id);
        SecurityUtils.checkCommunityAccess(feedback.getCommunityId());
        validateTransition(feedback, "CLOSED");
        feedback.setStatus("CLOSED");
        feedback.setHandlerId(SecurityUtils.getUserId());
        feedbackMapper.updateById(feedback);

        FeedbackMessage message = new FeedbackMessage();
        message.setFeedbackId(id);
        message.setSenderId(SecurityUtils.getUserId());
        message.setSenderType("ADMIN");
        message.setContent("[办结] " + dto.getRemark());
        messageMapper.insert(message);
        notificationService.create(feedback.getResidentId(), feedback.getCommunityId(),
                "反馈已办结", "您的反馈「" + feedback.getTitle() + "」已办结",
                "FEEDBACK", "FEEDBACK", id);
        log.info("反馈已办结：feedbackId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /**
     * 发送会话消息：居民限本人反馈；管理员首次发消息自动受理（PENDING → IN_SESSION）；
     * 已办结的反馈不可继续会话。
     */
    @Transactional(rollbackFor = Exception.class)
    public MessageVO sendMessage(Long feedbackId, SendMessageDTO dto) {
        Feedback feedback = requireFeedback(feedbackId);
        boolean isAdminSide = !SecurityUtils.hasRole(RoleConstants.RESIDENT);
        if (isAdminSide) {
            SecurityUtils.checkCommunityAccess(feedback.getCommunityId());
        } else if (!feedback.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权在他人反馈下发消息");
        }

        if ("CLOSED".equals(feedback.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "反馈已办结，不可继续会话");
        }
        /* 管理员受理：首次回复将会话激活 */
        if (isAdminSide && "PENDING".equals(feedback.getStatus())) {
            validateTransition(feedback, "IN_SESSION");
            feedback.setStatus("IN_SESSION");
            feedback.setHandlerId(SecurityUtils.getUserId());
            feedbackMapper.updateById(feedback);
        }

        FeedbackMessage message = new FeedbackMessage();
        message.setFeedbackId(feedbackId);
        message.setSenderId(SecurityUtils.getUserId());
        message.setSenderType(isAdminSide ? "ADMIN" : "RESIDENT");
        message.setContent(dto.getContent());
        message.setParentId(dto.getParentId());
        messageMapper.insert(message);

        /* 会话消息触达对端：管理员回复通知居民；居民追问通知处理人（未受理时无处理人则跳过） */
        if (isAdminSide) {
            notificationService.create(feedback.getResidentId(), feedback.getCommunityId(),
                    "反馈有新回复", "您的反馈「" + feedback.getTitle() + "」有新回复",
                    "FEEDBACK", "FEEDBACK", feedbackId);
        } else if (feedback.getHandlerId() != null) {
            notificationService.create(feedback.getHandlerId(), feedback.getCommunityId(),
                    "反馈有新消息", "反馈「" + feedback.getTitle() + "」有居民新消息",
                    "FEEDBACK", "FEEDBACK", feedbackId);
        }

        /* R30 会话实时推送：广播到 /topic/feedback/{id}（订阅鉴权保证仅参与者收到）；
           双方均离线不推送，推送失败不回滚——前端轮询兜底即离线补拉语义 */
        if (webSocketSessionService.isOnline(feedback.getResidentId())
                || (feedback.getHandlerId() != null
                        && webSocketSessionService.isOnline(feedback.getHandlerId()))) {
            try {
                messagingTemplate.convertAndSend("/topic/feedback/" + feedbackId,
                        FeedbackPushVO.message(toVO(message, feedback.getResidentId())));
            } catch (Exception e) {
                log.warn("反馈会话 WebSocket 推送失败，由轮询兜底：feedbackId={}", feedbackId, e);
            }
        }
        return toVO(message, feedback.getResidentId());
    }

    /**
     * 会话消息列表：支持 since 增量拉取（P1 HTTP 轮询；P2 升级 WebSocket 后保留兜底）。
     */
    public List<MessageVO> messages(Long feedbackId, LocalDateTime since) {
        Feedback feedback = requireFeedback(feedbackId);
        checkReadAccess(feedback);
        return messageMapper.selectList(new LambdaQueryWrapper<FeedbackMessage>()
                        .eq(FeedbackMessage::getFeedbackId, feedbackId)
                        .gt(since != null, FeedbackMessage::getCreatedAt, since)
                        .orderByAsc(FeedbackMessage::getId))
                .stream().map(m -> toVO(m, feedback.getResidentId())).toList();
    }

    /* 附件列表：访问权限与详情同口径 */
    public List<AttachmentVO> attachments(Long feedbackId) {
        Feedback feedback = requireFeedback(feedbackId);
        checkReadAccess(feedback);
        return attachmentMapper.selectList(new LambdaQueryWrapper<FeedbackAttachment>()
                        .eq(FeedbackAttachment::getFeedbackId, feedbackId)
                        .orderByAsc(FeedbackAttachment::getId))
                .stream().map(AttachmentVO::from).toList();
    }

    /**
     * 上传反馈附件（接口设计.md 9.6.2.1，BE-ISSUE-9）：居民限本人反馈，
     * 管理员放行（数据级权限已过滤）；已办结反馈不可补传。
     */
    @Transactional(rollbackFor = Exception.class)
    public AttachmentVO uploadAttachment(Long feedbackId, MultipartFile file) {
        Feedback feedback = requireFeedback(feedbackId);
        checkWriteAccess(feedback);
        if ("CLOSED".equals(feedback.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "反馈已办结，不可上传附件");
        }
        FileUploadService.UploadResult uploaded = fileUploadService.uploadAutoType(file);
        FeedbackAttachment attachment = new FeedbackAttachment();
        attachment.setFeedbackId(feedbackId);
        attachment.setFileName(uploaded.fileName());
        attachment.setFileUrl(uploaded.fileUrl());
        attachment.setFileType(uploaded.fileType());
        attachment.setFileSize(uploaded.fileSize());
        attachmentMapper.insert(attachment);
        return AttachmentVO.from(attachment);
    }

    /* 删除反馈附件（接口设计.md 9.6.2.2）：权限与上传同口径；物理文件保留（与工单附件一致） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long attachmentId) {
        FeedbackAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new ResourceNotFoundException("附件不存在");
        }
        Feedback feedback = requireFeedback(attachment.getFeedbackId());
        checkWriteAccess(feedback);
        if ("CLOSED".equals(feedback.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "反馈已办结，不可删除附件");
        }
        attachmentMapper.deleteById(attachmentId);
    }

    /* 附件写权限：居民限本人反馈；管理端角色放行（社区归属在 Controller 入口由数据级权限过滤） */
    private void checkWriteAccess(Feedback feedback) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !feedback.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅可操作本人反馈的附件");
        }
    }

    public Feedback requireFeedback(Long id) {
        Feedback feedback = feedbackMapper.selectById(id);
        if (feedback == null) {
            throw new ResourceNotFoundException("反馈不存在");
        }
        return feedback;
    }

    private void validateTransition(Feedback feedback, String target) {
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(feedback.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("反馈状态不允许从 %s 流转到 %s", feedback.getStatus(), target));
        }
    }

    private void checkReadAccess(Feedback feedback) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !feedback.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人反馈");
        }
    }

    private FeedbackVO toVO(Feedback feedback) {
        FeedbackVO vo = FeedbackVO.from(feedback);
        Resident resident = residentMapper.selectById(feedback.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        if (feedback.getHandlerId() != null) {
            SysUser handler = sysUserMapper.selectById(feedback.getHandlerId());
            if (handler != null) {
                vo.setHandlerName(handler.getRealName());
            }
        }
        return vo;
    }

    private MessageVO toVO(FeedbackMessage message, Long feedbackResidentId) {
        MessageVO vo = MessageVO.from(message);
        if ("RESIDENT".equals(message.getSenderType())) {
            Resident resident = residentMapper.selectById(message.getSenderId());
            if (resident != null) {
                vo.setSenderName(resident.getRealName());
            }
        } else {
            SysUser user = sysUserMapper.selectById(message.getSenderId());
            if (user != null) {
                vo.setSenderName(user.getRealName());
            }
        }
        return vo;
    }
}
