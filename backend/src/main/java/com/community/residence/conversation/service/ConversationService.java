package com.community.residence.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ConversationType;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.conversation.dto.SendMessageDTO;
import com.community.residence.conversation.entity.Conversation;
import com.community.residence.conversation.entity.ConversationMessage;
import com.community.residence.conversation.entity.ConversationParticipant;
import com.community.residence.conversation.mapper.ConversationMapper;
import com.community.residence.conversation.mapper.ConversationMessageMapper;
import com.community.residence.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.conversation.vo.ConversationMessageVO;
import com.community.residence.conversation.vo.ConversationPushVO;
import com.community.residence.conversation.vo.ConversationVO;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 多方会话业务逻辑（R63）：会话/参与者/消息三级模型。
 * 看房预约群聊（预约居民 + 该社区启用管理员 + 带看人，建群/入群由
 * ViewingAppointmentService 调 createViewingGroup/addParticipant）+
 * 居民-社区管理员直通（openDirect 幂等）。未读 = COUNT(消息 id > 已读游标)。
 * 发消息后 WS 广播 /topic/conversation/{id}（复制 R59 pushMessage 模式：
 * 全员离线不推送、推送失败不回滚 WARN，HTTP 轮询兜底；订阅鉴权见
 * ConversationSubscriptionInterceptor）。非参与者一律 ForbiddenException。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationParticipantMapper participantMapper;
    private final ConversationMessageMapper messageMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;

    /**
     * 看房预约建群（R63，ViewingAppointmentService 创建预约后调用）：
     * 居民 user_id 为空（游客预约）返回 null 不建群；入群=预约居民 +
     * 该社区启用 ADMIN（sys_admin_community 绑定 + sys_user 启用）。
     * 幂等：同 related_id 的 VIEWING_GROUP 已存在直接返回。
     * 标题=「看房沟通·{housingTitle}」，口径同 V16 存量回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public Conversation createViewingGroup(Long appointmentId, Long communityId,
                                           Long residentUserId, String housingTitle) {
        if (residentUserId == null) {
            return null;
        }
        Conversation existing = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getType, ConversationType.VIEWING_GROUP)
                        .eq(Conversation::getRelatedId, appointmentId)
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.VIEWING_GROUP);
        conversation.setTitle("看房沟通·" + (housingTitle != null ? housingTitle : ""));
        conversation.setCommunityId(communityId);
        conversation.setRelatedId(appointmentId);
        conversationMapper.insert(conversation);

        addParticipant(conversation.getId(), residentUserId);
        for (SysUser admin : activeCommunityAdmins(communityId)) {
            addParticipant(conversation.getId(), admin.getId());
        }
        return conversation;
    }

    /**
     * 入群（幂等，uk_conversation_user 撞号静默忽略）：带看人分配
     * （ViewingAppointmentService.assign）与直通会话创建复用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void addParticipant(Long conversationId, Long userId) {
        boolean exists = participantMapper.exists(new LambdaQueryWrapper<ConversationParticipant>()
                .eq(ConversationParticipant::getConversationId, conversationId)
                .eq(ConversationParticipant::getUserId, userId));
        if (!exists) {
            ConversationParticipant participant = new ConversationParticipant();
            participant.setConversationId(conversationId);
            participant.setUserId(userId);
            participant.setLastReadMessageId(0L);
            participantMapper.insert(participant);
        }
    }

    /**
     * 居民-社区管理员直通会话（R63，幂等）：同（DIRECT，社区，居民）已存在
     * 直接返回；不存在则建会话并拉入发起居民 + 该社区全部启用 ADMIN。
     * 居民须属于该社区（在住居住关系或生效租约），防跨社区直通。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO openDirect(Long communityId) {
        Long residentId = SecurityUtils.getUserId();

        /* 幂等命中：本人参与的 DIRECT 会话中找该社区的一条 */
        List<Long> mine = participantMapper.selectList(
                        new LambdaQueryWrapper<ConversationParticipant>()
                                .eq(ConversationParticipant::getUserId, residentId))
                .stream().map(ConversationParticipant::getConversationId).toList();
        if (!mine.isEmpty()) {
            Conversation existing = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .in(Conversation::getId, mine)
                            .eq(Conversation::getType, ConversationType.DIRECT)
                            .eq(Conversation::getCommunityId, communityId)
                            .last("LIMIT 1"));
            if (existing != null) {
                return toVO(existing, residentId);
            }
        }

        Long relationCount = residenceRelationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getResidentId, residentId)
                .eq(ResidenceRelation::getCommunityId, communityId)
                .isNull(ResidenceRelation::getMoveOutDate));
        Long leaseCount = leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getTenantId, residentId)
                .eq(LeaseRecord::getCommunityId, communityId)
                .eq(LeaseRecord::getStatus, "ACTIVE"));
        if ((relationCount == null ? 0 : relationCount) == 0 && (leaseCount == null ? 0 : leaseCount) == 0) {
            throw new ForbiddenException("您不属于该社区，无法发起直通会话");
        }

        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.DIRECT);
        conversation.setTitle("社区直通会话");
        conversation.setCommunityId(communityId);
        conversationMapper.insert(conversation);
        addParticipant(conversation.getId(), residentId);
        List<SysUser> admins = activeCommunityAdmins(communityId);
        for (SysUser admin : admins) {
            addParticipant(conversation.getId(), admin.getId());
        }
        if (admins.isEmpty()) {
            log.warn("直通会话创建时社区无启用管理员（居民单方会话）：communityId={}, residentId={}",
                    communityId, residentId);
        }
        return toVO(conversation, residentId);
    }

    /**
     * 我的会话列表（参与者视角，R63）：当前用户参与的会话分页，含未读数
     * （消息 id > 已读游标）与最后消息摘要（冗余列）；type 过滤可选，
     * 按会话 id 倒序（新会话在前）。
     */
    public PageVO<ConversationVO> page(long page, long size, String type) {
        Long userId = SecurityUtils.getUserId();
        List<Long> conversationIds = participantMapper.selectList(
                        new LambdaQueryWrapper<ConversationParticipant>()
                                .eq(ConversationParticipant::getUserId, userId))
                .stream().map(ConversationParticipant::getConversationId).toList();
        if (conversationIds.isEmpty()) {
            return PageVO.of(List.of(), 0, page, size);
        }
        Page<Conversation> result = conversationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<Conversation>()
                        .in(Conversation::getId, conversationIds)
                        .eq(StringUtils.hasText(type), Conversation::getType, type)
                        .orderByDesc(Conversation::getId));
        List<ConversationVO> records = result.getRecords().stream()
                .map(c -> toVO(c, userId)).toList();
        return PageVO.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 会话消息列表（R63）：时间正序（id 升序单调）非分页；仅参与者可读。
     */
    public List<ConversationMessageVO> listMessages(Long conversationId) {
        requireParticipant(conversationId);
        return messageMapper.selectList(new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByAsc(ConversationMessage::getId))
                .stream().map(this::toMessageVO).toList();
    }

    /**
     * 发送会话消息（R63）：仅参与者可发；落库（createdAt 显式赋值，WS 载荷
     * 与响应即时携带真实时间）后同事务维护会话最后消息冗余列，WS 广播
     * /topic/conversation/{id}（载荷 {type:'CONVERSATION_MESSAGE', data:VO}；
     * 全员离线不推送、推送失败不回滚，HTTP 轮询兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessageVO sendMessage(Long conversationId, SendMessageDTO dto) {
        Conversation conversation = requireParticipant(conversationId);
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSenderId(SecurityUtils.getUserId());
        message.setSenderRole(currentRole());
        message.setContent(dto.getContent());
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        conversation.setLastMessage(dto.getContent());
        conversation.setLastMessageAt(message.getCreatedAt());
        conversationMapper.updateById(conversation);

        ConversationMessageVO vo = toMessageVO(message);
        pushMessage(conversation, vo);
        return vo;
    }

    /**
     * 标记已读（R63）：更新参与者已读游标（仅前移，防回退把旧消息变未读）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long conversationId, Long lastMessageId) {
        ConversationParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversationId)
                        .eq(ConversationParticipant::getUserId, SecurityUtils.getUserId())
                        .last("LIMIT 1"));
        if (participant == null) {
            /* 命中 404（会话不存在）或 403（非参与者），与读路径同口径 */
            requireParticipant(conversationId);
        }
        if (participant != null && lastMessageId > participant.getLastReadMessageId()) {
            participant.setLastReadMessageId(lastMessageId);
            participantMapper.updateById(participant);
        }
    }

    /* 当前认证角色（消息落库的 sender_role；无认证上下文兜底 RESIDENT 不可达，
      因读路径已要求参与者身份） */
    private String currentRole() {
        return SecurityUtils.getUser() != null
                ? Objects.requireNonNullElse(SecurityUtils.getUser().getRole(), RoleConstants.RESIDENT)
                : RoleConstants.RESIDENT;
    }

    /* 参与者校验：会话不存在 404，非参与者 403（与订阅鉴权同口径） */
    private Conversation requireParticipant(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        Long userId = SecurityUtils.getUserId();
        boolean participating = participantMapper.exists(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversationId)
                        .eq(ConversationParticipant::getUserId, userId));
        if (!participating) {
            throw new ForbiddenException("非会话参与者，无权访问会话");
        }
        return conversation;
    }

    /* R63 会话实时推送（复制 R59 pushMessage 模式）：全员离线不推送，
       推送失败不回滚 WARN——前端轮询兜底 */
    private void pushMessage(Conversation conversation, ConversationMessageVO vo) {
        List<Long> participantIds = participantMapper.selectList(
                        new LambdaQueryWrapper<ConversationParticipant>()
                                .eq(ConversationParticipant::getConversationId, conversation.getId()))
                .stream().map(ConversationParticipant::getUserId).toList();
        boolean anyOnline = participantIds.stream()
                .anyMatch(webSocketSessionService::isOnline);
        if (!anyOnline) {
            return;
        }
        try {
            messagingTemplate.convertAndSend("/topic/conversation/" + conversation.getId(),
                    ConversationPushVO.message(vo));
        } catch (Exception e) {
            log.warn("会话 WebSocket 推送失败，由轮询兜底：conversationId={}", conversation.getId(), e);
        }
    }

    /* 发送人姓名：按 sender_role 选表（RESIDENT 取 resident.real_name，
       其余取 sys_user.real_name），消解两套自增序列撞号 */
    private ConversationMessageVO toMessageVO(ConversationMessage message) {
        ConversationMessageVO vo = ConversationMessageVO.from(message);
        if (RoleConstants.RESIDENT.equals(message.getSenderRole())) {
            Resident resident = residentMapper.selectById(message.getSenderId());
            vo.setSenderName(resident != null ? resident.getRealName() : "");
        } else {
            SysUser user = sysUserMapper.selectById(message.getSenderId());
            vo.setSenderName(user != null ? user.getRealName() : "");
        }
        return vo;
    }

    /* 未读数 = 会话内消息 id > 本人已读游标的条数 */
    private ConversationVO toVO(Conversation conversation, Long userId) {
        ConversationVO vo = ConversationVO.from(conversation);
        ConversationParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversation.getId())
                        .eq(ConversationParticipant::getUserId, userId)
                        .last("LIMIT 1"));
        long cursor = participant != null ? participant.getLastReadMessageId() : 0L;
        Long unread = messageMapper.selectCount(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversation.getId())
                .gt(ConversationMessage::getId, cursor));
        vo.setUnreadCount(unread != null ? unread : 0L);
        return vo;
    }

    /* 社区启用 ADMIN 清单（建群/直通入群口径，与 V16 存量回填一致） */
    private List<SysUser> activeCommunityAdmins(Long communityId) {
        List<Long> adminIds = sysAdminCommunityMapper.selectList(
                        new LambdaQueryWrapper<SysAdminCommunity>()
                                .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList();
        if (adminIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, adminIds)
                .eq(SysUser::getRole, RoleConstants.ADMIN)
                .eq(SysUser::getStatus, CommonStatus.ACTIVE));
    }
}
