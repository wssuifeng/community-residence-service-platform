package com.community.residence.common.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.conversation.dto.SendMessageDTO;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationMessage;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.conversation.vo.ConversationMessageVO;
import com.community.residence.common.conversation.vo.ConversationParticipantVO;
import com.community.residence.common.conversation.vo.ConversationPushVO;
import com.community.residence.common.conversation.vo.ConversationVO;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 多方会话业务逻辑（R63）：会话/参与者/消息三级模型。
 * 看房预约群聊（预约居民 + 该小区启用管理员 + 带看人，建群/入群由
 * ViewingAppointmentService 调 createViewingGroup/addParticipant）+
 * 居民-小区管理员直通（openDirect 幂等）。未读 = COUNT(消息 id > 已读游标)。
 * 发消息后 WS 广播 /topic/conversation/{id}（复制 R59 pushMessage 模式：
 * 全员离线不推送、推送失败不回滚 WARN，HTTP 轮询兜底；订阅鉴权见
 * ConversationSubscriptionInterceptor）。非参与者一律 ForbiddenException。
 *
 * DEF-075（身份空间收口，口径同 V21/notification）：参与者的完整身份是
 * **(owner_type, user_id) 二元组**——居民空间取 resident.id、员工/管理空间取
 * sys_user.id，两表自增序列独立且主键可撞号（resident 1 vs sys_user 1）。
 * 参与者判定 / 成员展示 / 未读与已读 / 推送在线判定 / WS 订阅鉴权
 * **一律按二元组**，禁止只按 user_id（否则撞号者会越权进入他人会话）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery;
    private final ChatImageService chatImageService;

    /** 系统消息发送人角色（群聊状态变更留痕，非任何账号发言） */
    private static final String SYSTEM_SENDER_ROLE = "SYSTEM";

    /** 系统消息发送人 ID 占位：多态 sender_id 不设外键，0 不指向任何账号 */
    private static final Long SYSTEM_SENDER_ID = 0L;

    private final ConversationMapper conversationMapper;
    private final ConversationParticipantMapper participantMapper;
    private final ConversationMessageMapper messageMapper;
    private final com.community.residence.common.agreement.mapper.LeaseAgreementMapper agreementMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;

    /**
     * 看房预约建群（R63，ViewingAppointmentService 创建预约后调用）：
     * 居民 user_id 为空（历史匿名预约）返回 null 不建群；入群=预约居民 +
     * 该小区启用 ADMIN（sys_admin_community 绑定 + sys_user 启用）。
     * 幂等：同 related_id 的 VIEWING_GROUP 已存在直接返回。
     * 标题=「看房沟通·{housingTitle}」，口径同 V16 存量回填。
     */
    @Transactional(rollbackFor = Exception.class)
    public Conversation createViewingGroup(Long appointmentId, Long communityId,
                                           Long residentUserId, String housingTitle) {
        if (residentUserId == null) {
            return null;
        }
        Conversation existing = findTypedGroup(ConversationType.VIEWING_GROUP, appointmentId);
        if (existing != null) {
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.VIEWING_GROUP);
        conversation.setTitle("看房沟通·" + (housingTitle != null ? housingTitle : ""));
        conversation.setCommunityId(communityId);
        conversation.setRelatedId(appointmentId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversationMapper.insert(conversation);

        addParticipant(conversation.getId(), NotificationOwnerType.RESIDENT, residentUserId);
        for (SysUser admin : activeCommunityAdmins(communityId)) {
            addParticipant(conversation.getId(), NotificationOwnerType.USER, admin.getId());
        }
        return conversation;
    }

    /**
     * 租约群聊建群（R68，入住申请审核通过后由 ResidenceApplicationService 调用）：
     * 参与者 = 申请居民 + 该小区全部启用 ADMIN；related_id = 租约 ID。
     * 幂等：同 related_id 的 LEASE_GROUP 已存在直接返回（同一租约不重复建群）；
     * 库级唯一键 uk_conversation_type_related（V27）+ 撞键重查兜底先查后插的
     * 并发窗口（DEF-106）；居民账号为空（无居民身份的历史数据）时不建群。
     */
    @Transactional(rollbackFor = Exception.class)
    public Conversation createLeaseGroup(Long leaseId, Long communityId,
                                         Long residentUserId, String title) {
        if (residentUserId == null) {
            return null;
        }
        /* 同一租约先取得父行锁再当前读，避免并发撞唯一键后共享锁升级的死锁。 */
        leaseRecordMapper.selectByIdForUpdate(leaseId);
        Conversation existing = findTypedGroup(ConversationType.LEASE_GROUP, leaseId, true);
        if (existing != null) {
            return existing;
        }
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.LEASE_GROUP);
        conversation.setTitle(title != null ? title : "租约沟通");
        conversation.setCommunityId(communityId);
        conversation.setRelatedId(leaseId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            /* 并发建群撞 uk_conversation_type_related（DEF-106）：先查后插窗口内
               另一路已建群，重查返回既有会话（同一租约不产生第二群，参与者/消息
               不分裂）；重查仍为空属异常场景（约束归属不明等），原样抛出不吞 */
            Conversation winner = findTypedGroup(ConversationType.LEASE_GROUP, leaseId, true);
            if (winner != null) {
                return winner;
            }
            throw e;
        }

        addParticipant(conversation.getId(), NotificationOwnerType.RESIDENT, residentUserId);
        List<SysUser> admins = activeCommunityAdmins(communityId);
        for (SysUser admin : admins) {
            addParticipant(conversation.getId(), NotificationOwnerType.USER, admin.getId());
        }
        if (admins.isEmpty()) {
            log.warn("租约群聊创建时小区无启用管理员（居民单方会话）：communityId={}, leaseId={}",
                    communityId, leaseId);
        }
        return conversation;
    }

    /**
     * 建/取租约沟通会话（DEF-076，幂等）：审批通过内核已自动建群，本入口供
     * **存量/旁路数据缺会话时由管理端手动补建**，或在会话中心按 leaseId 直接进入。
     * 已存在同 related_id 的 LEASE_GROUP 直接返回（不重复建群/入群），并在
     * `lease_record.conversation_id` 未回写时补写回链——该回链同时是承约前置
     * （DEF-077「租约沟通会话已建立」）的判定依据，故补建后可继续承约流程。
     * 已关闭的会话原样返回（撤回/驳回/归档后的关闭态不自动重开）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO ensureLeaseGroup(Long leaseId) {
        LeaseRecord lease = leaseRecordMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        Conversation conversation = createLeaseGroup(leaseId, lease.getCommunityId(),
                lease.getTenantId(), "租约沟通");
        if (conversation == null) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "该租约无居民账号，无法建立沟通会话");
        }
        if (!conversation.getId().equals(lease.getConversationId())) {
            /* 回链只补写 conversation_id 字段（DEF-119）：全字段 updateById 会把
               读取时刻的整租约快照写回，并发的状态流转（TO_SIGN→ACTIVE 等）会被
               静默覆盖——条件更新仅触碰回链字段；内存对象同步仅为本地一致性，
               不再参与任何落库 */
            leaseRecordMapper.update(null, new LambdaUpdateWrapper<LeaseRecord>()
                    .eq(LeaseRecord::getId, leaseId)
                    .set(LeaseRecord::getConversationId, conversation.getId()));
            lease.setConversationId(conversation.getId());
            log.info("租约沟通会话补建/补写回链：leaseId={}, conversationId={}, operator={}",
                    leaseId, conversation.getId(), SecurityUtils.getUserId());
        }
        return toVO(conversation, currentOwnerType(), SecurityUtils.getUserId());
    }

    /**
     * 关闭租约群聊（R68 生命周期终止）：申请撤回/驳回或租约归档/搬出时关闭，
     * 关闭后停收停发、历史只读留档；幂等（已关闭或未建群直接返回）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeLeaseGroup(Long leaseId) {
        Conversation conversation = findTypedGroup(ConversationType.LEASE_GROUP, leaseId);
        if (conversation == null) {
            return;
        }
        closeConversation(conversation);
    }

    /** 子看房预约只能关闭自己所属的独立看房群，不能按裸会话ID关闭租约群。 */
    @Transactional(rollbackFor=Exception.class)
    public void closeViewingGroup(Long appointmentId) {
        Conversation ownGroup=findTypedGroup(ConversationType.VIEWING_GROUP,appointmentId);
        if(ownGroup!=null) closeConversation(ownGroup.getId(),"看房预约已取消");
    }

    /**
     * 关闭会话（幂等，R68 生命周期终止点入口）：会话不存在直接返回（未建群即无需关闭），
     * 否则置 CLOSED + 关闭时间；reason 仅入日志留痕（关闭原因不作业务判定）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeConversation(Long conversationId, String reason) {
        if (conversationId == null) {
            return;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            return;
        }
        closeConversation(conversation);
        log.info("会话按外部终止点关闭：conversationId={}, reason={}", conversationId, reason);
    }

    /* ==================== R74 工单临时群聊（复用既有三级模型，不新增表/端点） ==================== */

    /**
     * 工单临时群聊建群（R74，WorkOrderService 受理成功后**同一事务**调用）：
     * 参与者 = **居民提交人**（resident.id）+ **受理管理员**（sys_user.id）；
     * `type=WORK_ORDER_GROUP`、`related_id=work_order.id`、`status=ACTIVE`，
     * 标题「工单沟通·<工单号>」。
     * 幂等：同 related_id 的 WORK_ORDER_GROUP 已存在直接复用（**同一工单不重复建群**）。
     * 居民账号为空（历史脏数据）时返回 null 不建群。
     */
    @Transactional(rollbackFor = Exception.class)
    public Conversation createWorkOrderGroup(Long workOrderId, Long communityId, Long residentUserId,
                                            Long intakeAdminId, String orderNo) {
        Conversation existing = findWorkOrderGroup(workOrderId);
        if (existing != null) {
            /* 命中即复用：补登受理管理员（首次建群失败重试 / 换管理员受理的场景），仍保持单群 */
            if (intakeAdminId != null) {
                addParticipant(existing.getId(), NotificationOwnerType.USER, intakeAdminId);
            }
            return existing;
        }
        if (residentUserId == null) {
            log.warn("工单临时群聊未建群：居民提交人缺失，workOrderId={}", workOrderId);
            return null;
        }
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.WORK_ORDER_GROUP);
        conversation.setTitle("工单沟通·" + (StringUtils.hasText(orderNo) ? orderNo : workOrderId));
        conversation.setCommunityId(communityId);
        conversation.setRelatedId(workOrderId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            /* 并发建群撞 uk_conversation_type_related（DEF-106）：受理重试/换管理员
               受理与首建并发时，重查复用既有群并补登本次受理人（addParticipant 幂等），
               与上方先查命中路径同一语义；重查仍为空属异常场景，原样抛出不吞 */
            Conversation winner = findTypedGroup(ConversationType.WORK_ORDER_GROUP, workOrderId, true);
            if (winner != null) {
                if (intakeAdminId != null) {
                    addParticipant(winner.getId(), NotificationOwnerType.USER, intakeAdminId);
                }
                return winner;
            }
            throw e;
        }

        addParticipant(conversation.getId(), NotificationOwnerType.RESIDENT, residentUserId);
        if (intakeAdminId != null) {
            addParticipant(conversation.getId(), NotificationOwnerType.USER, intakeAdminId);
        }
        return conversation;
    }

    /** 取工单临时群聊（无则 null；派单入群 / 终审回收复用同一判定口径） */
    public Conversation findWorkOrderGroup(Long workOrderId) {
        if (workOrderId == null) {
            return null;
        }
        return findTypedGroup(ConversationType.WORK_ORDER_GROUP, workOrderId);
    }

    /* 同类型同业务主体的既有会话查询（DEF-106 唯一化后的幂等键）：
       V27 uk_conversation_type_related 保证 (type, related_id) 至多一条，
       LIMIT 1 仅兜底防御性去重前的历史脏数据 */
    private Conversation findTypedGroup(String type, Long relatedId) {
        return findTypedGroup(type, relatedId, false);
    }

    /* 撞唯一键后的查询必须是当前读，普通一致性读仍可能停在插入之前的快照。 */
    private Conversation findTypedGroup(String type, Long relatedId, boolean currentRead) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getType, type)
                .eq(Conversation::getRelatedId, relatedId)
                .last(currentRead ? "LIMIT 1 FOR UPDATE" : "LIMIT 1"));
    }

    /**
     * 派单后被派服务人员**幂等入群**（R74：§9.4.2.5 内部接线，不新增端点）。
     * 未建群（未经受理直接派单）时静默跳过——**受理是唯一建群入口**（§9.21.2 硬口径），
     * 入群只做成员补齐，不反向补建会话。返回是否命中群聊。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean joinWorkOrderGroup(Long workOrderId, Long staffId, String staffName) {
        Conversation conversation = findWorkOrderGroup(workOrderId);
        if (conversation == null || staffId == null) {
            log.info("工单临时群聊不存在，跳过服务人员入群：workOrderId={}, staffId={}", workOrderId, staffId);
            return false;
        }
        /* 会话已关闭即拒绝入群（DEF-119 纵深防御）：终审回收后群已停收停发，
           不得再以派单入口拉人进群（口径对齐 sendMessage 的 5604） */
        if (ConversationStatus.CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CONVERSATION_CLOSED);
        }
        long before = participantMapper.selectCount(new LambdaQueryWrapper<ConversationParticipant>()
                .eq(ConversationParticipant::getConversationId, conversation.getId())
                .eq(ConversationParticipant::getOwnerType, NotificationOwnerType.USER)
                .eq(ConversationParticipant::getUserId, staffId));
        addParticipant(conversation.getId(), NotificationOwnerType.USER, staffId);
        if (before == 0) {
            sendSystemMessage(conversation.getId(),
                    "服务人员 " + (StringUtils.hasText(staffName) ? staffName : staffId) + " 已加入工单沟通群");
        }
        return true;
    }

    /**
     * 工单临时群聊**资源回收**（R74，管理端终审关闭工单后调用）：
     * 置 `status=CLOSED` + 写 `closed_at`（复用 V20 既有两列），
     * **只读留痕、不物理删除**（历史消息与会话记录全部保留）；
     * 幂等：未建群直接返回，已关闭重复调用不报错。
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeWorkOrderGroup(Long workOrderId) {
        Conversation conversation = findWorkOrderGroup(workOrderId);
        if (conversation == null) {
            return;
        }
        closeConversation(conversation);
    }

    /** 关闭会话（幂等）：置 CLOSED + 关闭时间，供租约群聊生命周期各终止点复用 */
    @Transactional(rollbackFor = Exception.class)
    public void closeConversation(Conversation conversation) {
        if (ConversationStatus.CLOSED.equals(conversation.getStatus())) {
            return;
        }
        conversation.setStatus(ConversationStatus.CLOSED);
        conversation.setClosedAt(LocalDateTime.now());
        conversationMapper.updateById(conversation);
        log.info("会话已关闭：conversationId={}, type={}, relatedId={}",
                conversation.getId(), conversation.getType(), conversation.getRelatedId());
    }

    /**
     * 写入群聊系统消息（R68：签约生效等状态变化的留痕，非任何参与者发言）：
     * sender_role=SYSTEM + sender_id=0（多态列不设外键，0 不指向任何账号），
     * 消息落库后同步会话最后消息冗余列并按会话推送。
     * 会话不存在或内容为空时静默跳过；**会话已关闭（CLOSED）抛
     * CONVERSATION_CLOSED(5604)**（DEF-119 纵深防御，停收停发与 sendMessage
     * 同口径）——唯一合法的关群留痕由调用方保证「先发后关」次序
     * （先例：WorkOrderService.close 先留痕再 closeWorkOrderGroup）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void sendSystemMessage(Long conversationId, String content) {
        insertSystemMessage(conversationId,content,"TEXT",null);
    }

    /* 协议事件由协议服务调用；校验同租约归属，普通发言不能伪造业务卡片。 */
    @Transactional(rollbackFor=Exception.class)
    public void sendAgreementMessage(Long conversationId, Long agreementId, String content) {
        if(conversationId==null) return;
        var conversation=conversationMapper.selectById(conversationId);
        var agreement=agreementMapper.selectById(agreementId);
        if(conversation==null || agreement==null || !ConversationType.LEASE_GROUP.equals(conversation.getType())
            || !java.util.Objects.equals(conversation.getRelatedId(),agreement.getLeaseId()))
            throw new BusinessException(ErrorCode.INVALID_PARAM,"协议消息与租约会话不匹配");
        insertSystemMessage(conversationId,content,"LEASE_AGREEMENT",agreementId);
    }

    private void insertSystemMessage(Long conversationId, String content, String messageType, Long agreementId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !StringUtils.hasText(content)) {
            return;
        }
        /* 会话已关闭停发（DEF-119）：系统留痕与用户消息同口径拒绝 5604，
           关闭后的会话只读留档 */
        if (ConversationStatus.CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CONVERSATION_CLOSED);
        }
        ConversationMessage message = new ConversationMessage();
        message.setConversationId(conversationId);
        message.setSenderId(SYSTEM_SENDER_ID);
        message.setSenderRole(SYSTEM_SENDER_ROLE);
        message.setContent(content);
        message.setMessageType(messageType);
        message.setAgreementId(agreementId);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);

        conversation.setLastMessage(content);
        conversation.setLastMessageAt(message.getCreatedAt());
        conversationMapper.updateById(conversation);
        pushMessage(conversation, toMessageVO(message));
    }

    /**
     * 入群（幂等，`uk_cp_identity` 撞号静默忽略）：**必须显式给出身份空间**——
     * 完整身份是 (ownerType, userId) 二元组，只传 user_id 无法区分
     * resident 1 与 sys_user 1（DEF-075）。
     *
     * @param ownerType {@link NotificationOwnerType#RESIDENT}（居民，userId=resident.id）
     *                  或 {@link NotificationOwnerType#USER}（员工/管理员，userId=sys_user.id）
     */
    @Transactional(rollbackFor = Exception.class)
    public void addParticipant(Long conversationId, String ownerType, Long userId) {
        boolean exists = participantMapper.exists(new LambdaQueryWrapper<ConversationParticipant>()
                .eq(ConversationParticipant::getConversationId, conversationId)
                .eq(ConversationParticipant::getOwnerType, ownerType)
                .eq(ConversationParticipant::getUserId, userId));
        if (!exists) {
            ConversationParticipant participant = new ConversationParticipant();
            participant.setConversationId(conversationId);
            participant.setOwnerType(ownerType);
            participant.setUserId(userId);
            participant.setLastReadMessageId(0L);
            try {
                participantMapper.insert(participant);
            } catch (DuplicateKeyException e) {
                /* 前置存在性检查与 insert 之间的并发窗口由 uk_cp_identity 兜底
                   （DEF-119）：本方法 Javadoc 早已声明「撞号静默忽略」，此处真正落实
                   ——已有同二元组参与者行即幂等成功；debug 级留观测痕迹，不升 error
                   （属预期内的并发竞态，非故障） */
                log.debug("参与者并发入群撞 uk_cp_identity，按幂等忽略：conversationId={}, ownerType={}, userId={}",
                        conversationId, ownerType, userId, e);
            }
        }
    }

    /**
     * 兼容入口（DEF-075 遗留签名，**仅限 sys_user 主体**）：现存唯一调用点是
     * 看房带看人分配（`ViewingAppointmentService#assign`，入群人是 STAFF 账号），
     * 故按 {@link NotificationOwnerType#USER} 收口；居民入群一律走三参显式重载
     * （由守卫用例 {@code ConversationParticipantOwnerTypeGuardTest} 防止新增误用）。
     *
     * @deprecated 新调用点请用 {@link #addParticipant(Long, String, Long)} 显式声明身份空间
     */
    @Deprecated
    @Transactional(rollbackFor = Exception.class)
    public void addParticipant(Long conversationId, Long userId) {
        addParticipant(conversationId, NotificationOwnerType.USER, userId);
    }

    /** 居民侧直通会话（R63）：发起人 = 当前登录居民本人 */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO openDirect(Long communityId) {
        return openDirect(communityId, SecurityUtils.getUserId());
    }

    /**
     * 居民-小区管理员直通会话（R63/DEF-076，幂等）：同（DIRECT，小区，居民）已存在
     * 直接返回；不存在则建会话并拉入该居民 + 该小区全部启用 ADMIN。
     * 发起人两种：居民本人（residentId = 当前登录居民）与**管理方代居民发起**
     * （管理端会话中心「开启会话」，residentId = 指定居民，DEF-076 补齐管理端缺口）；
     * 两入口幂等键一致，谁先发起都命中同一条会话。
     * 居民须属于该小区（在住居住关系或生效租约），防跨小区直通。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationVO openDirect(Long communityId, Long residentId) {
        Long callerId = SecurityUtils.getUserId();
        /* 身份空间分派（DEF-075/DEF-076）：仅当调用方**明确是管理方**（ADMIN/SUPER_ADMIN 且非居民）
           才走「代居民发起」，须显式指定 residentId 并校验小区归属；
           其余（居民本人入口，含未标注角色的内部调用）一律取当前登录主体为居民对象 */
        boolean adminInitiated = !SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && (SecurityUtils.hasRole(RoleConstants.ADMIN)
                    || SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN));
        if (adminInitiated) {
            SecurityUtils.checkCommunityAccess(communityId);
            if (residentId == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "请指定直通会话的居民");
            }
            if (residentMapper.selectById(residentId) == null) {
                throw new ResourceNotFoundException("居民不存在");
            }
        } else {
            if (residentId != null && !residentId.equals(callerId)) {
                throw new ForbiddenException("居民仅可为本人发起直通会话");
            }
            residentId = callerId;
        }
        String callerSpace = currentOwnerType();

        /* 直通没有可锁的新会话行；先锁居民父行直到事务提交，串行化同一居民的建群。
           后续用当前读，避免等待父锁前形成的可重复读快照漏掉另一事务刚提交的会话。 */
        residentMapper.selectByIdForUpdate(residentId);

        /* 幂等命中：该居民（居民身份空间）参与的 DIRECT 会话中找该小区的一条 */
        List<Long> mine = participantMapper.selectList(
                        new LambdaQueryWrapper<ConversationParticipant>()
                                .eq(ConversationParticipant::getOwnerType, NotificationOwnerType.RESIDENT)
                                .eq(ConversationParticipant::getUserId, residentId)
                                .last("FOR UPDATE"))
                .stream().map(ConversationParticipant::getConversationId).toList();
        if (!mine.isEmpty()) {
            Conversation existing = conversationMapper.selectOne(
                    new LambdaQueryWrapper<Conversation>()
                            .in(Conversation::getId, mine)
                            .eq(Conversation::getType, ConversationType.DIRECT)
                            .eq(Conversation::getCommunityId, communityId)
                            .last("LIMIT 1 FOR UPDATE"));
            if (existing != null) {
                return toVO(existing, callerSpace, callerId);
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
            throw new ForbiddenException("您不属于该小区，无法发起直通会话");
        }

        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.DIRECT);
        conversation.setTitle("小区直通会话");
        conversation.setCommunityId(communityId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversationMapper.insert(conversation);
        addParticipant(conversation.getId(), NotificationOwnerType.RESIDENT, residentId);
        List<SysUser> admins = activeCommunityAdmins(communityId);
        for (SysUser admin : admins) {
            addParticipant(conversation.getId(), NotificationOwnerType.USER, admin.getId());
        }
        if (admins.isEmpty()) {
            log.warn("直通会话创建时小区无启用管理员（居民单方会话）：communityId={}, residentId={}",
                    communityId, residentId);
        }
        return toVO(conversation, callerSpace, callerId);
    }

    /**
     * 我的会话列表（参与者视角，R63）：当前用户参与的会话分页，含未读数
     * （消息 id > 已读游标）与最后消息摘要（冗余列）；type 过滤可选，
     * 按会话 id 倒序（新会话在前）。
     */
    public PageVO<ConversationVO> page(long page, long size, String type) {
        Long userId = SecurityUtils.getUserId();
        String ownerType = currentOwnerType();
        List<Long> conversationIds = participantMapper.selectList(
                        new LambdaQueryWrapper<ConversationParticipant>()
                                .eq(ConversationParticipant::getOwnerType, ownerType)
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
                .map(c -> toVO(c, ownerType, userId)).toList();
        return PageVO.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    /**
     * 会话详情（R68 契约补充）：仅参与者可见（非参与者 403，会话不存在 404），
     * 返回含 status/type/relatedId 与参与者概要（群成员展示）的详情。
     */
    public ConversationVO getById(Long conversationId) {
        Conversation conversation = requireParticipant(conversationId);
        Long userId = SecurityUtils.getUserId();
        ConversationVO vo = toVO(conversation, currentOwnerType(), userId);
        List<ConversationParticipantVO> participants = participantProfiles(conversationId);
        vo.setParticipants(participants);
        vo.setParticipantCount(participants.size());
        return vo;
    }

    /**
     * 取租约群聊并校验调用者为参与者（R69 会话内业务发起的前置校验）：
     * 会话不存在 404、非参与者 403、会话类型非租约群聊拒绝。
     */
    public Conversation requireLeaseGroup(Long conversationId) {
        Conversation conversation = requireParticipant(conversationId);
        if (!ConversationType.LEASE_GROUP.equals(conversation.getType())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "该会话不是租约群聊，不支持会话内发起业务");
        }
        return conversation;
    }

    /**
     * 会话消息列表（R63）：时间正序（id 升序单调）非分页；仅参与者可读。
     */
    public List<ConversationMessageVO> listMessages(Long conversationId) {
        requireParticipant(conversationId);
        var rows=messageMapper.selectList(new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getConversationId, conversationId)
                        .orderByAsc(ConversationMessage::getId));
        var ids=rows.stream().filter(m->com.community.residence.common.conversation.constant.ChatMessageType.IMAGE.equals(m.getMessageType())).map(ConversationMessage::getId).toList();
        var images=ids.isEmpty()?java.util.Map.<Long,com.community.residence.common.conversation.vo.ChatImageVO>of():chatImageService.images(ChatImageService.CONVERSATION,ids);
        return rows.stream().map(m->toMessageVO(m,images.get(m.getId()))).toList();
    }

    /* 历史分段与增量补拉仍先校验参与者；倒序取窗后正序展示。 */
    public List<ConversationMessageVO> messageWindow(Long id, Long beforeId, Long afterId, int size) {
        requireParticipant(id);
        var query = new LambdaQueryWrapper<ConversationMessage>().eq(ConversationMessage::getConversationId,id)
                .lt(beforeId!=null,ConversationMessage::getId,beforeId).gt(afterId!=null,ConversationMessage::getId,afterId)
                .orderBy(afterId==null,false,ConversationMessage::getId).orderBy(afterId!=null,true,ConversationMessage::getId)
                .last("LIMIT "+size);
        var rows = new ArrayList<>(messageMapper.selectList(query));
        rows.sort(java.util.Comparator.comparing(ConversationMessage::getId));
        var ids=rows.stream().filter(m->"IMAGE".equals(m.getMessageType())).map(ConversationMessage::getId).toList();
        var images=ids.isEmpty()?java.util.Map.<Long,com.community.residence.common.conversation.vo.ChatImageVO>of():chatImageService.images(ChatImageService.CONVERSATION,ids);
        return rows.stream().map(m->toMessageVO(m,images.get(m.getId()))).toList();
    }

    /**
     * 发送会话消息（R63）：仅参与者可发；**会话已关闭（R68 status=CLOSED）时停收停发**，
     * 返回 CONVERSATION_CLOSED(5604)，历史消息仍可读；落库（createdAt 显式赋值，WS 载荷
     * 与响应即时携带真实时间）后同事务维护会话最后消息冗余列，WS 广播
     * /topic/conversation/{id}（载荷 {type:'CONVERSATION_MESSAGE', data:VO}；
     * 全员离线不推送、推送失败不回滚，HTTP 轮询兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ConversationMessageVO sendMessage(Long conversationId, SendMessageDTO dto) { return send(conversationId, dto, null); }

    @Transactional(rollbackFor = Exception.class)
    public ConversationMessageVO sendImage(Long conversationId, org.springframework.web.multipart.MultipartFile file) {
        SendMessageDTO dto = new SendMessageDTO(); dto.setContent("[图片]");
        return send(conversationId, dto, file);
    }

    /* 仅复用已完成参与者/关闭态校验的消息流程，图片不能通过普通TEXT载荷伪造。 */
    private ConversationMessageVO send(Long conversationId, SendMessageDTO dto, org.springframework.web.multipart.MultipartFile file) {
        Conversation conversation = requireParticipant(conversationId);
        if (ConversationStatus.CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CONVERSATION_CLOSED);
        }
        var uploaded = file == null ? null : chatImageService.upload(file);
        ConversationMessage message = new ConversationMessage();
        message.setMessageType(uploaded == null ? com.community.residence.common.conversation.constant.ChatMessageType.TEXT : com.community.residence.common.conversation.constant.ChatMessageType.IMAGE);
        message.setConversationId(conversationId);
        message.setSenderId(SecurityUtils.getUserId());
        message.setSenderRole(currentRole());
        message.setContent(dto.getContent());
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        if (uploaded != null) chatImageService.attach(ChatImageService.CONVERSATION, message.getId(), conversation.getCommunityId(), uploaded);

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
        String ownerType = currentOwnerType();
        Long userId = SecurityUtils.getUserId();
        ConversationParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversationId)
                        .eq(ConversationParticipant::getOwnerType, ownerType)
                        .eq(ConversationParticipant::getUserId, userId)
                        .last("LIMIT 1"));
        if (participant == null) {
            /* 命中 404（会话不存在）或 403（非参与者），与读路径同口径 */
            requireParticipant(conversationId);
        }
        ConversationMessage lastMessage = lastMessageId == null ? null : messageMapper.selectById(lastMessageId);
        if (lastMessage == null || !Objects.equals(lastMessage.getConversationId(), conversationId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "已读消息不属于当前会话");
        }
        long previous = participant != null && participant.getLastReadMessageId() != null ? participant.getLastReadMessageId() : 0L;
        if (lastMessageId > previous) {
            participantMapper.advanceReadCursor(conversationId, ownerType, userId, lastMessageId);
        }
    }

    /* 当前认证角色（消息落库的 sender_role；无认证上下文兜底 RESIDENT 不可达，
      因读路径已要求参与者身份） */
    private String currentRole() {
        return SecurityUtils.getUser() != null
                ? Objects.requireNonNullElse(SecurityUtils.getUser().getRole(), RoleConstants.RESIDENT)
                : RoleConstants.RESIDENT;
    }

    /* 当前认证主体所属身份空间：取请求级 UserContext.role（与 JwtAuthenticationFilter /
       IdentityPrincipal 同源）；无认证主体回落 USER 空间（=员工/管理，即"非居民"），
       避免未认证主体被误当作某个同号居民（DEF-075 判定必须失败关闭） */
    private String currentOwnerType() {
        UserContext user = SecurityUtils.getUser();
        return NotificationOwnerType.ofRole(user != null ? user.getRole() : null);
    }

    /* 参与者校验：会话不存在 404，非参与者 403（与订阅鉴权同口径）。
       DEF-075：按 (owner_type, user_id) 二元组判定——仅 user_id 相同不再算参与者 */
    private Conversation requireParticipant(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        String ownerType = currentOwnerType();
        Long userId = SecurityUtils.getUserId();
        boolean participating = participantMapper.exists(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversationId)
                        .eq(ConversationParticipant::getOwnerType, ownerType)
                        .eq(ConversationParticipant::getUserId, userId));
        if (!participating) {
            throw new ForbiddenException("非会话参与者，无权访问会话");
        }
        return conversation;
    }

    /* R63 会话实时推送（复制 R59 pushMessage 模式）：全员离线不推送，
       推送失败不回滚 WARN——前端轮询兜底。
       参与者表已按 (owner_type, user_id) 收口（DEF-075），在线判定按各自身份空间
       精确匹配（居民空间查居民在线表、员工/管理空间查系统用户在线表）——
       撞号的另一空间主体不再误触发推送；投递安全仍由主题订阅鉴权
       （ConversationSubscriptionInterceptor）保证 */
    private void pushMessage(Conversation conversation, ConversationMessageVO vo) {
        List<ConversationParticipant> participants = participantMapper.selectList(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversation.getId()));
        boolean anyOnline = participants.stream()
                .anyMatch(p -> NotificationOwnerType.RESIDENT.equals(p.getOwnerType())
                        ? webSocketSessionService.isResidentOnline(p.getUserId())
                        : webSocketSessionService.isUserOnline(p.getUserId()));
        if (!anyOnline) {
            return;
        }
        try {
            String destination = "/topic/conversation/" + conversation.getId();
            ConversationPushVO payload = ConversationPushVO.message(vo);
            afterCommitDelivery.deliver(destination, () -> messagingTemplate.convertAndSend(destination, payload));
        } catch (Exception e) {
            log.warn("会话 WebSocket 推送失败，由轮询兜底：conversationId={}", conversation.getId(), e);
        }
    }

    /* 发送人姓名：按 sender_role 选表（RESIDENT 取 resident.real_name，
       其余取 sys_user.real_name），消解两套自增序列撞号；SYSTEM 为系统留痕 */
    private ConversationMessageVO toMessageVO(ConversationMessage message) {
        var image = com.community.residence.common.conversation.constant.ChatMessageType.IMAGE.equals(message.getMessageType()) ? chatImageService.image(ChatImageService.CONVERSATION, message.getId()) : null;
        return toMessageVO(message, image);
    }

    private ConversationMessageVO toMessageVO(ConversationMessage message, com.community.residence.common.conversation.vo.ChatImageVO image) {
        ConversationMessageVO vo = ConversationMessageVO.from(message);
        vo.setImage(image);
        if (SYSTEM_SENDER_ROLE.equals(message.getSenderRole())) {
            vo.setSenderName("系统");
            return vo;
        }
        if (RoleConstants.RESIDENT.equals(message.getSenderRole())) {
            Resident resident = residentMapper.selectById(message.getSenderId());
            vo.setSenderName(resident != null ? resident.getRealName() : "");
        } else {
            SysUser user = sysUserMapper.selectById(message.getSenderId());
            vo.setSenderName(user != null ? user.getRealName() : "");
        }
        return vo;
    }

    /* 未读数 = 会话内消息 id > 本人已读游标的条数；
       DEF-075：已读游标按 (owner_type, user_id) 二元组取 */
    private ConversationVO toVO(Conversation conversation, String ownerType, Long userId) {
        ConversationVO vo = ConversationVO.from(conversation);
        ConversationParticipant participant = participantMapper.selectOne(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversation.getId())
                        .eq(ConversationParticipant::getOwnerType, ownerType)
                        .eq(ConversationParticipant::getUserId, userId)
                        .last("LIMIT 1"));
        long cursor = participant != null && participant.getLastReadMessageId() != null ? participant.getLastReadMessageId() : 0L;
        Long unread = messageMapper.selectCount(new LambdaQueryWrapper<ConversationMessage>()
                .eq(ConversationMessage::getConversationId, conversation.getId())
                .gt(ConversationMessage::getId, cursor)
                .and(other -> {
                    /* resident.id与sys_user.id可相同，不能只按数字ID排除本人。 */
                    other.ne(ConversationMessage::getSenderId, userId).or();
                    if ("RESIDENT".equals(ownerType)) other.ne(ConversationMessage::getSenderRole, RoleConstants.RESIDENT);
                    else other.eq(ConversationMessage::getSenderRole, RoleConstants.RESIDENT);
                }));
        vo.setUnreadCount(unread != null ? unread : 0L);
        return vo;
    }

    /* 参与者概要（会话详情 R68，DEF-075 修正）：**按每行自己的身份空间取表**——
       owner_type=RESIDENT 取 resident.real_name，owner_type=USER 取 sys_user
       （姓名/角色）。旧实现"sys_user 命中优先"会把 resident 1 展示成 sys_user 1
       （如超级管理员），即可被用于越权辨识；现不再跨空间兜底。 */
    private List<ConversationParticipantVO> participantProfiles(Long conversationId) {
        List<ConversationParticipant> rows = participantMapper.selectList(
                new LambdaQueryWrapper<ConversationParticipant>()
                        .eq(ConversationParticipant::getConversationId, conversationId)
                        .orderByAsc(ConversationParticipant::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> sysUserIds = rows.stream()
                .filter(p -> NotificationOwnerType.USER.equals(p.getOwnerType()))
                .map(ConversationParticipant::getUserId).distinct().toList();
        List<Long> residentIds = rows.stream()
                .filter(p -> !NotificationOwnerType.USER.equals(p.getOwnerType()))
                .map(ConversationParticipant::getUserId).distinct().toList();
        /* 两空间各自批量查，空集合不查库（MyBatis-Plus selectBatchIds 空列表行为不保证） */
        Map<Long, SysUser> sysUsers = sysUserIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(sysUserIds).stream()
                        .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
        Map<Long, Resident> residents = residentIds.isEmpty() ? Map.of()
                : residentMapper.selectBatchIds(residentIds).stream()
                        .collect(Collectors.toMap(Resident::getId, r -> r, (a, b) -> a));

        List<ConversationParticipantVO> profiles = new ArrayList<>(rows.size());
        for (ConversationParticipant row : rows) {
            Long userId = row.getUserId();
            if (NotificationOwnerType.USER.equals(row.getOwnerType())) {
                SysUser sysUser = sysUsers.get(userId);
                if (sysUser == null) {
                    log.warn("会话参与者指向不存在的 sys_user：conversationId={}, userId={}", conversationId, userId);
                    profiles.add(ConversationParticipantVO.of(userId, NotificationOwnerType.USER, "", ""));
                    continue;
                }
                profiles.add(ConversationParticipantVO.of(userId, NotificationOwnerType.USER,
                        StringUtils.hasText(sysUser.getRealName()) ? sysUser.getRealName() : sysUser.getUsername(),
                        sysUser.getRole()));
                continue;
            }
            Resident resident = residents.get(userId);
            if (resident == null) {
                log.warn("会话参与者指向不存在的 resident：conversationId={}, userId={}", conversationId, userId);
            }
            profiles.add(ConversationParticipantVO.of(userId, NotificationOwnerType.RESIDENT,
                    resident != null ? resident.getRealName() : "", RoleConstants.RESIDENT));
        }
        return profiles;
    }

    /* 小区启用 ADMIN 清单（建群/直通入群口径，与 V16 存量回填一致） */
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
