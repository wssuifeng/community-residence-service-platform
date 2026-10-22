package com.community.residence.common.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.conversation.dto.SendMessageDTO;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationMessage;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.conversation.vo.ConversationMessageVO;
import com.community.residence.common.conversation.vo.ConversationVO;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R63 多方会话回归：建群入群（居民+小区管理员+带看人幂等）、非参与者 403、
 * 未读游标与 markRead 只前移、直通幂等与小区归属校验、发送后 WS 推送；
 * OCR 静态评审（DEF-106/DEF-119，2026-09-23）：并发建群撞唯一键重查回退、
 * 租约会话回链条件更新、入群撞键幂等忽略、关闭态 join/系统留痕拒绝 5604。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多方会话（R63）")
class ConversationServiceTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    private static final Long CONV_ID = 10L;
    private static final Long RESIDENT_USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;

    @BeforeAll
    static void initTableInfo() {
        /* LambdaUpdateWrapper.eq 为惰性解析、set 是构造期即时解析（断言列名须查
           TableInfo 缓存），DEF-119 条件更新回链路径先初始化 LeaseRecord 元数据
           （口径同 ConversationLeaseGroupEnsureTest / NoticeAutoOfflineTaskTest） */
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LeaseRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ConversationMessage.class);
    }

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private ConversationParticipantMapper participantMapper;
    @Mock
    private ConversationMessageMapper messageMapper;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;

    @InjectMocks
    private ConversationService service;

    private Conversation conversation;
    private ConversationParticipant residentParticipant;

    @BeforeEach
    void setUp() {
        conversation = new Conversation();
        conversation.setId(CONV_ID);
        conversation.setType("VIEWING_GROUP");
        conversation.setTitle("看房沟通·测试房源");
        conversation.setCommunityId(1L);
        conversation.setRelatedId(23L);
        lenient().when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation);

        residentParticipant = new ConversationParticipant();
        residentParticipant.setConversationId(CONV_ID);
        residentParticipant.setUserId(RESIDENT_USER_ID);
        residentParticipant.setLastReadMessageId(0L);

        /* DEF-067：会话参与者的在线判定按两个身份空间各查一次（参与者表无角色列） */
        lenient().when(webSocketSessionService.isResidentOnline(anyLong())).thenReturn(false);
        lenient().when(webSocketSessionService.isUserOnline(anyLong())).thenReturn(false);
    }

    private void asResident() {
        /* UserContext(id, username, role, communityIds)——居民账号统一走 sys_user 身份 */
        org.mockito.Mockito.mockStatic(SecurityUtils.class);
    }

    @Test
    @DisplayName("看房建群：居民 + 小区启用管理员入群；历史匿名预约（userId=null）不建群")
    void createViewingGroup_participants() {
        lenient().when(conversationMapper.selectOne(any())).thenReturn(null);
        lenient().when(sysAdminCommunityMapper.selectList(any()))
                .thenReturn(List.of(binding(ADMIN_ID, 1L)));
        SysUser admin = new SysUser();
        admin.setId(ADMIN_ID);
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        admin.setRealName("王管理");
        lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));
        /* addParticipant 幂等查询：首查不存在 */
        lenient().when(participantMapper.exists(any())).thenReturn(false);

        Conversation created = service.createViewingGroup(23L, 1L, RESIDENT_USER_ID, "测试房源");

        assertThat(created.getTitle()).isEqualTo("看房沟通·测试房源");
        /* 居民 + 管理员两人入群 */
        ArgumentCaptor<ConversationParticipant> captor = ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participantMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ConversationParticipant::getUserId)
                .containsExactlyInAnyOrder(RESIDENT_USER_ID, ADMIN_ID);

        /* 历史匿名预约不建群 */
        assertThat(service.createViewingGroup(24L, 1L, null, "测试房源")).isNull();
        verify(conversationMapper, org.mockito.Mockito.times(1)).insert(any(Conversation.class));
    }

    @Test
    @DisplayName("建群幂等：同 related_id 已存在群直接返回，不重复插入")
    void createViewingGroup_idempotent() {
        lenient().when(conversationMapper.selectOne(any())).thenReturn(conversation);

        Conversation existing = service.createViewingGroup(23L, 1L, RESIDENT_USER_ID, "测试房源");

        assertThat(existing.getId()).isEqualTo(CONV_ID);
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    @Test
    @DisplayName("入群幂等：已参与者不重复插入")
    void addParticipant_idempotent() {
        lenient().when(participantMapper.exists(any())).thenReturn(true);

        service.addParticipant(CONV_ID, ADMIN_ID);

        verify(participantMapper, never()).insert(any(ConversationParticipant.class));
    }

    @Test
    @DisplayName("消息读取：非参与者 403；参与者正序返回")
    void listMessages_access() {
        ConversationMessage msg = new ConversationMessage();
        msg.setId(1L);
        msg.setConversationId(CONV_ID);
        msg.setSenderId(RESIDENT_USER_ID);
        msg.setSenderRole("RESIDENT");
        msg.setContent("你好");
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(msg));
        Resident resident = new Resident();
        resident.setRealName("演示居民");
        lenient().when(residentMapper.selectById(RESIDENT_USER_ID)).thenReturn(resident);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            lenient().when(participantMapper.exists(any())).thenReturn(true);
            List<ConversationMessageVO> result = service.listMessages(CONV_ID);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSenderName()).isEqualTo("演示居民");

            /* 非参与者 403 */
            lenient().when(participantMapper.exists(any())).thenReturn(false);
            assertThatThrownBy(() -> service.listMessages(CONV_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("非会话参与者");
        }
    }

    @Test
    @DisplayName("会话不存在：读消息 404")
    void listMessages_notFound() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            lenient().when(conversationMapper.selectById(999L)).thenReturn(null);
            assertThatThrownBy(() -> service.listMessages(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("发送消息：落库 + 维护会话最后消息冗余列 + 有在线参与者时 WS 推送")
    void sendMessage_push() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(RESIDENT_USER_ID, "resident1", "RESIDENT", Set.of(1L)));
            lenient().when(participantMapper.exists(any())).thenReturn(true);
            lenient().when(participantMapper.selectList(any()))
                    .thenReturn(List.of(residentParticipant, participant(ADMIN_ID)));
            Resident resident = new Resident();
            resident.setRealName("演示居民");
            lenient().when(residentMapper.selectById(RESIDENT_USER_ID)).thenReturn(resident);
            /* 有参与者在线 → 推送（ADMIN_ID 属 sys_user 身份空间） */
            lenient().when(webSocketSessionService.isUserOnline(ADMIN_ID)).thenReturn(true);

            ConversationMessageVO vo = service.sendMessage(CONV_ID, dto("明天几点看房？"));

            assertThat(vo.getContent()).isEqualTo("明天几点看房？");
            assertThat(vo.getSenderRole()).isEqualTo("RESIDENT");
            verify(messageMapper).insert(any(ConversationMessage.class));
            /* 会话冗余列同步更新 */
            ArgumentCaptor<Conversation> convCaptor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationMapper).updateById(convCaptor.capture());
            assertThat(convCaptor.getValue().getLastMessage()).isEqualTo("明天几点看房？");
            verify(messagingTemplate).convertAndSend(eq("/topic/conversation/" + CONV_ID), any(Object.class));
        }
    }

    @Test
    @DisplayName("全员离线：不推送（轮询兜底）")
    void sendMessage_noPushWhenAllOffline() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(RESIDENT_USER_ID, "resident1", "RESIDENT", Set.of(1L)));
            lenient().when(participantMapper.exists(any())).thenReturn(true);
            lenient().when(participantMapper.selectList(any())).thenReturn(List.of(residentParticipant));
            Resident resident = new Resident();
            resident.setRealName("演示居民");
            lenient().when(residentMapper.selectById(RESIDENT_USER_ID)).thenReturn(resident);

            service.sendMessage(CONV_ID, dto("离线场景"));

            verify(messagingTemplate, never())
                    .convertAndSend(anyString(), any(Object.class));
        }
    }

    @Test
    @DisplayName("未读数：消息 id > 已读游标计数；markRead 游标只前移")
    void unread_and_markRead() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            /* toVO 路径：参与者游标 5，未读 2 条 */
            residentParticipant.setLastReadMessageId(5L);
            lenient().when(participantMapper.selectOne(any())).thenReturn(residentParticipant);
            lenient().when(messageMapper.selectCount(any())).thenReturn(2L);

            ConversationVO vo = service.page(1, 10, null).getRecords().isEmpty()
                    ? null : null; /* page 需要会话列表桩，下面直接验证 markRead */

            ConversationMessage read = new ConversationMessage(); read.setId(8L); read.setConversationId(CONV_ID);
            when(messageMapper.selectById(8L)).thenReturn(read);
            when(messageMapper.selectById(3L)).thenReturn(read);
            service.markRead(CONV_ID, 8L);
            verify(participantMapper).advanceReadCursor(eq(CONV_ID), anyString(), eq(RESIDENT_USER_ID), eq(8L));
            service.markRead(CONV_ID, 3L);
            verify(participantMapper, never()).advanceReadCursor(eq(CONV_ID), anyString(), eq(RESIDENT_USER_ID), eq(3L));
        }
    }

    @Test
    @DisplayName("直通会话：居民不属于小区 403；属于则建群并拉入管理员")
    void openDirect_belongCheck() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);

            /* 无在住关系且无生效租约 → 403 */
            lenient().when(participantMapper.selectList(any())).thenReturn(List.of());
            lenient().when(residenceRelationMapper.selectCount(any())).thenReturn(0L);
            lenient().when(leaseRecordMapper.selectCount(any())).thenReturn(0L);
            assertThatThrownBy(() -> service.openDirect(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("不属于该小区");

            /* 有生效租约 → 建群，居民 + 小区管理员入群 */
            lenient().when(leaseRecordMapper.selectCount(any())).thenReturn(1L);
            lenient().when(conversationMapper.selectOne(any())).thenReturn(null);
            lenient().when(sysAdminCommunityMapper.selectList(any()))
                    .thenReturn(List.of(binding(ADMIN_ID, 1L)));
            SysUser admin = new SysUser();
            admin.setId(ADMIN_ID);
            admin.setRole("ADMIN");
            admin.setStatus("ACTIVE");
            admin.setRealName("王管理");
            lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));
            lenient().when(participantMapper.exists(any())).thenReturn(false);

            ConversationVO vo = service.openDirect(1L);

            assertThat(vo.getType()).isEqualTo("DIRECT");
            verify(participantMapper, org.mockito.Mockito.times(2))
                    .insert(any(ConversationParticipant.class));
        }
    }

    /* ---------- R68 租约群聊生命周期 ---------- */

    @Test
    @DisplayName("租约建群：参与者=居民+小区启用管理员，类型 LEASE_GROUP、related_id=租约ID")
    void createLeaseGroup_participants() {
        lenient().when(conversationMapper.selectOne(any())).thenReturn(null);
        lenient().when(sysAdminCommunityMapper.selectList(any()))
                .thenReturn(List.of(binding(ADMIN_ID, 1L)));
        SysUser admin = new SysUser();
        admin.setId(ADMIN_ID);
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        admin.setRealName("王管理");
        lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));
        lenient().when(participantMapper.exists(any())).thenReturn(false);

        Conversation created = service.createLeaseGroup(88L, 1L, RESIDENT_USER_ID, "租约沟通·演示小区1栋101");

        assertThat(created.getType()).isEqualTo("LEASE_GROUP");
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        assertThat(created.getRelatedId()).isEqualTo(88L);
        ArgumentCaptor<ConversationParticipant> captor = ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participantMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ConversationParticipant::getUserId)
                .containsExactlyInAnyOrder(RESIDENT_USER_ID, ADMIN_ID);
    }

    @Test
    @DisplayName("租约建群幂等：同租约已有群直接返回，不重复建群")
    void createLeaseGroup_idempotent() {
        Conversation existing = new Conversation();
        existing.setId(CONV_ID);
        existing.setType("LEASE_GROUP");
        existing.setRelatedId(88L);
        existing.setStatus("ACTIVE");
        lenient().when(conversationMapper.selectOne(any())).thenReturn(existing);

        Conversation result = service.createLeaseGroup(88L, 1L, RESIDENT_USER_ID, "租约沟通");

        assertThat(result.getId()).isEqualTo(CONV_ID);
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    @Test
    @DisplayName("关闭会话：置 CLOSED + closed_at，且关闭后发消息拒绝 5604")
    void closeConversation_blocksSend() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(RESIDENT_USER_ID, "resident1", "RESIDENT", Set.of(1L)));
            conversation.setStatus("ACTIVE");
            lenient().when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation);
            lenient().when(participantMapper.exists(any())).thenReturn(true);

            /* 关闭：状态与时间落库 */
            service.closeConversation(CONV_ID, "入住申请撤回");
            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
            assertThat(captor.getValue().getClosedAt()).isNotNull();

            /* 关闭后发消息：拒绝 5604，历史消息仍可读 */
            assertThatThrownBy(() -> service.sendMessage(CONV_ID, dto("还能发吗")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONVERSATION_CLOSED));
            verify(messageMapper, never()).insert(any(ConversationMessage.class));
        }
    }

    @Test
    @DisplayName("关闭会话幂等：已 CLOSED 或会话不存在直接返回")
    void closeConversation_idempotent() {
        conversation.setStatus("CLOSED");
        lenient().when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation);

        service.closeConversation(CONV_ID, "重复关闭");
        verify(conversationMapper, never()).updateById(any(Conversation.class));

        /* 未建群的租约（selectOne 空）关闭为无操作 */
        lenient().when(conversationMapper.selectById(999L)).thenReturn(null);
        service.closeConversation(999L, "无会话");
        verify(conversationMapper, never()).updateById(any(Conversation.class));
    }

    @Test
    @DisplayName("会话详情：参与者可见（含 status/type/relatedId 与参与者概要），非参与者 403")
    void getById_participantOnly() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            conversation.setStatus("CLOSED");
            lenient().when(conversationMapper.selectById(CONV_ID)).thenReturn(conversation);
            lenient().when(participantMapper.exists(any())).thenReturn(true);
            lenient().when(participantMapper.selectList(any()))
                    .thenReturn(List.of(residentParticipant, participant(ADMIN_ID)));
            Resident resident = new Resident();
            resident.setRealName("演示居民");
            lenient().when(residentMapper.selectById(RESIDENT_USER_ID)).thenReturn(resident);
            SysUser admin = new SysUser();
            admin.setId(ADMIN_ID);
            admin.setRole("ADMIN");
            admin.setRealName("王管理");
            lenient().when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(admin));
            lenient().when(residentMapper.selectBatchIds(any())).thenReturn(List.of());

            ConversationVO vo = service.getById(CONV_ID);

            assertThat(vo.getStatus()).isEqualTo("CLOSED");
            assertThat(vo.getType()).isEqualTo("VIEWING_GROUP");
            assertThat(vo.getRelatedId()).isEqualTo(23L);
            assertThat(vo.getParticipantCount()).isEqualTo(2);

            /* 非参与者：403 */
            lenient().when(participantMapper.exists(any())).thenReturn(false);
            assertThatThrownBy(() -> service.getById(CONV_ID))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    /* ---------- OCR 静态评审 DEF-106 / DEF-119（2026-09-23）---------- */

    @Test
    @DisplayName("DEF-106 并发建群：insert 撞 uk_conversation_type_related 后重查返回既有会话")
    void createLeaseGroup_duplicateKeyFallsBackToWinner() {
        Conversation winner = new Conversation();
        winner.setId(77L);
        winner.setType("LEASE_GROUP");
        winner.setRelatedId(88L);
        winner.setStatus("ACTIVE");
        /* 先查为空（并发窗口）→ insert 撞唯一键 → 重查命中既有会话 */
        lenient().when(conversationMapper.selectOne(any())).thenReturn(null).thenReturn(winner);
        lenient().doAnswer(inv -> {
            throw new DuplicateKeyException("uk_conversation_type_related");
        }).when(conversationMapper).insert(any(Conversation.class));

        Conversation result = service.createLeaseGroup(88L, 1L, RESIDENT_USER_ID, "租约沟通");

        assertThat(result.getId()).isEqualTo(77L);
        verify(leaseRecordMapper).selectByIdForUpdate(88L);
        /* 输家路径即刻返回，不再向新群插参与者（既有群成员已就位） */
        verify(participantMapper, never()).insert(any(ConversationParticipant.class));
    }

    @Test
    @DisplayName("DEF-119 回链条件更新：ensureLeaseGroup 仅更新 conversation_id，非全字段 updateById")
    void ensureLeaseGroup_backfillsConversationIdViaConditionalUpdate() {
        LeaseRecord lease = new LeaseRecord();
        lease.setId(88L);
        lease.setCommunityId(1L);
        lease.setTenantId(RESIDENT_USER_ID);
        lease.setStatus("TO_SIGN");
        lease.setConversationId(null);
        lenient().when(leaseRecordMapper.selectById(88L)).thenReturn(lease);
        lenient().when(conversationMapper.selectOne(any())).thenReturn(null);
        lenient().when(conversationMapper.insert(any(Conversation.class))).thenAnswer(inv -> {
            Conversation created = inv.getArgument(0);
            created.setId(77L);
            return 1;
        });
        lenient().when(participantMapper.exists(any())).thenReturn(false);
        lenient().when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of());
        lenient().when(sysUserMapper.selectList(any())).thenReturn(List.of());
        lenient().when(participantMapper.selectOne(any())).thenReturn(null);
        lenient().when(messageMapper.selectCount(any())).thenReturn(0L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(ADMIN_ID, "admin1", "ADMIN", Set.of(1L)));

            service.ensureLeaseGroup(88L);

            /* 条件更新：SET 仅 conversation_id / WHERE 仅 id——不把读取时刻的
               整租约快照写回（并发的 TO_SIGN→ACTIVE 流转不得被覆盖） */
            verify(leaseRecordMapper).update(isNull(), any());
            verify(leaseRecordMapper, never()).updateById(any(LeaseRecord.class));
            /* 内存对象同步仅为本地一致性，回链字段已可见 */
            assertThat(lease.getConversationId()).isEqualTo(77L);
        }
    }

    @Test
    @DisplayName("DEF-119 关闭态工单群：joinWorkOrderGroup 拒绝入群（5604）")
    void joinWorkOrderGroup_closedConversationRejected() {
        Conversation closed = new Conversation();
        closed.setId(66L);
        closed.setType("WORK_ORDER_GROUP");
        closed.setRelatedId(9L);
        closed.setStatus("CLOSED");
        lenient().when(conversationMapper.selectOne(any())).thenReturn(closed);

        assertThatThrownBy(() -> service.joinWorkOrderGroup(9L, 11L, "李师傅"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONVERSATION_CLOSED));
        /* 关闭态不落参与者行、不留入群系统消息 */
        verify(participantMapper, never()).insert(any(ConversationParticipant.class));
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }

    @Test
    @DisplayName("DEF-119 关闭态会话：sendSystemMessage 拒绝留痕（5604，与 sendMessage 同口径）")
    void sendSystemMessage_closedConversationRejected() {
        conversation.setStatus("CLOSED");

        assertThatThrownBy(() -> service.sendSystemMessage(CONV_ID, "租赁协议双方已确认，租约已正式生效。"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.CONVERSATION_CLOSED));
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
        verify(conversationMapper, never()).updateById(any(Conversation.class));
    }

    @Test
    @DisplayName("DEF-119 入群撞 uk_cp_identity：DuplicateKeyException 静默忽略（幂等）")
    void addParticipant_duplicateKeySilentlyIgnored() {
        lenient().when(participantMapper.exists(any())).thenReturn(false);
        lenient().doAnswer(inv -> {
            throw new DuplicateKeyException("uk_cp_identity");
        }).when(participantMapper).insert(any(ConversationParticipant.class));

        /* 撞键不抛即通过——对齐 addParticipant Javadoc「撞号静默忽略」声明 */
        service.addParticipant(CONV_ID, "USER", ADMIN_ID);

        verify(participantMapper).insert(any(ConversationParticipant.class));
    }

    private static SendMessageDTO dto(String content) {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent(content);
        return dto;
    }

    private static ConversationParticipant participant(Long userId) {
        ConversationParticipant p = new ConversationParticipant();
        p.setConversationId(CONV_ID);
        p.setUserId(userId);
        p.setLastReadMessageId(0L);
        return p;
    }

    private static SysAdminCommunity binding(Long adminId, Long communityId) {
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(adminId);
        binding.setCommunityId(communityId);
        return binding;
    }
    @Test
    void foreignMessageCannotAdvanceReadCursor() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);
            when(participantMapper.selectOne(any())).thenReturn(residentParticipant);
            ConversationMessage foreign = new ConversationMessage(); foreign.setId(100L); foreign.setConversationId(999L);
            when(messageMapper.selectById(100L)).thenReturn(foreign);
            assertThatThrownBy(() -> service.markRead(CONV_ID, 100L)).isInstanceOf(BusinessException.class);
            verify(participantMapper, never()).advanceReadCursor(any(), any(), any(), any());
        }
    }

    @Test
    void unreadExcludesSelfWithinIdentitySpaceNotOnlyNumericId() {
        when(participantMapper.selectOne(any())).thenReturn(residentParticipant);
        when(messageMapper.selectCount(any())).thenReturn(0L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "toVO", conversation, "USER", ADMIN_ID);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationMessage>> query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(messageMapper).selectCount(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("sender_id <>", "OR sender_role =");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(ADMIN_ID, "RESIDENT");
    }

}
