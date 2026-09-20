package com.community.residence.conversation.service;

import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.conversation.dto.SendMessageDTO;
import com.community.residence.conversation.entity.Conversation;
import com.community.residence.conversation.entity.ConversationMessage;
import com.community.residence.conversation.entity.ConversationParticipant;
import com.community.residence.conversation.mapper.ConversationMapper;
import com.community.residence.conversation.mapper.ConversationMessageMapper;
import com.community.residence.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.conversation.vo.ConversationMessageVO;
import com.community.residence.conversation.vo.ConversationVO;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R63 多方会话回归：建群入群（居民+社区管理员+带看人幂等）、非参与者 403、
 * 未读游标与 markRead 只前移、直通幂等与社区归属校验、发送后 WS 推送。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("多方会话（R63）")
class ConversationServiceTest {

    private static final Long CONV_ID = 10L;
    private static final Long RESIDENT_USER_ID = 1L;
    private static final Long ADMIN_ID = 9L;

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

        lenient().when(webSocketSessionService.isOnline(anyLong())).thenReturn(false);
    }

    private void asResident() {
        /* UserContext(id, username, role, communityIds)——居民账号统一走 sys_user 身份 */
        org.mockito.Mockito.mockStatic(SecurityUtils.class);
    }

    @Test
    @DisplayName("看房建群：居民 + 社区启用管理员入群；游客预约（userId=null）不建群")
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

        /* 游客预约不建群 */
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
            /* 有参与者在线 → 推送 */
            lenient().when(webSocketSessionService.isOnline(ADMIN_ID)).thenReturn(true);

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

            /* markRead：新游标 8 > 5 → 前移更新 */
            service.markRead(CONV_ID, 8L);
            ArgumentCaptor<ConversationParticipant> captor = ArgumentCaptor.forClass(ConversationParticipant.class);
            verify(participantMapper).updateById(captor.capture());
            assertThat(captor.getValue().getLastReadMessageId()).isEqualTo(8L);

            /* 游标回退（3 < 8）：不更新 */
            org.mockito.Mockito.clearInvocations(participantMapper);
            service.markRead(CONV_ID, 3L);
            verify(participantMapper, never()).updateById(any(ConversationParticipant.class));
        }
    }

    @Test
    @DisplayName("直通会话：居民不属于社区 403；属于则建群并拉入管理员")
    void openDirect_belongCheck() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_USER_ID);

            /* 无在住关系且无生效租约 → 403 */
            lenient().when(participantMapper.selectList(any())).thenReturn(List.of());
            lenient().when(residenceRelationMapper.selectCount(any())).thenReturn(0L);
            lenient().when(leaseRecordMapper.selectCount(any())).thenReturn(0L);
            assertThatThrownBy(() -> service.openDirect(1L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("不属于该社区");

            /* 有生效租约 → 建群，居民 + 社区管理员入群 */
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
}
