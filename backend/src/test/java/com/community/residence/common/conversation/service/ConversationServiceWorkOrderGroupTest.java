package com.community.residence.common.conversation.service;

import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.conversation.dto.SendMessageDTO;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationMessage;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R74 工单临时群聊回归（会话侧）：受理建群（成员=居民提交人+受理管理员）、
 * **同一工单不重复建群**、派单后服务人员幂等入群、终审关闭后**资源回收**
 *（CLOSED + closed_at、只读留痕不物理删除、再发言 5604、历史仍可读）、
 * 非参与者不可见/不可发言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("工单临时群聊（R74，会话侧）")
class ConversationServiceWorkOrderGroupTest {

    private static final Long GROUP_ID = 77L;
    private static final Long ORDER_ID = 5L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 9L;
    private static final Long STAFF_ID = 11L;
    private static final String ORDER_NO = "WO202609220001";

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

    @BeforeEach
    void setUp() {
        /* 建群插入后回填自增主键，参与者落库带真实会话 ID */
        lenient().when(conversationMapper.insert(ArgumentMatchers.<Conversation>any())).thenAnswer(invocation -> {
            Conversation inserted = invocation.getArgument(0);
            inserted.setId(GROUP_ID);
            return 1;
        });
        lenient().when(webSocketSessionService.isResidentOnline(anyLong())).thenReturn(false);
        lenient().when(webSocketSessionService.isUserOnline(anyLong())).thenReturn(false);
    }

    private Conversation group(String status) {
        Conversation conversation = new Conversation();
        conversation.setId(GROUP_ID);
        conversation.setType(ConversationType.WORK_ORDER_GROUP);
        conversation.setTitle("工单沟通·" + ORDER_NO);
        conversation.setCommunityId(1L);
        conversation.setRelatedId(ORDER_ID);
        conversation.setStatus(status);
        return conversation;
    }

    private SendMessageDTO message(String content) {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent(content);
        return dto;
    }

    @Test
    @DisplayName("①受理建群：type=WORK_ORDER_GROUP、related_id=工单ID、标题带工单号、居民提交人+受理管理员入群")
    void createWorkOrderGroup_membersAndType() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        when(participantMapper.exists(any())).thenReturn(false);

        Conversation created = service.createWorkOrderGroup(ORDER_ID, 1L, RESIDENT_ID, ADMIN_ID, ORDER_NO);

        assertThat(created.getType()).isEqualTo(ConversationType.WORK_ORDER_GROUP);
        assertThat(created.getRelatedId()).isEqualTo(ORDER_ID);
        assertThat(created.getCommunityId()).isEqualTo(1L);
        assertThat(created.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(created.getTitle()).isEqualTo("工单沟通·" + ORDER_NO);

        ArgumentCaptor<ConversationParticipant> captor = ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participantMapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(ConversationParticipant::getUserId)
                .containsExactlyInAnyOrder(RESIDENT_ID, ADMIN_ID);
        assertThat(captor.getAllValues())
                .allSatisfy(p -> assertThat(p.getConversationId()).isEqualTo(GROUP_ID));
    }

    @Test
    @DisplayName("⑧同一工单不重复建群：命中既有群直接复用，不重复插会话/成员")
    void createWorkOrderGroup_idempotent() {
        Conversation existing = group(ConversationStatus.ACTIVE);
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        when(participantMapper.exists(any())).thenReturn(true);

        Conversation created = service.createWorkOrderGroup(ORDER_ID, 1L, RESIDENT_ID, ADMIN_ID, ORDER_NO);

        assertThat(created.getId()).isEqualTo(GROUP_ID);
        verify(conversationMapper, never()).insert(ArgumentMatchers.<Conversation>any());
        verify(participantMapper, never()).insert(ArgumentMatchers.<ConversationParticipant>any());
    }

    @Test
    @DisplayName("③派单后被派服务人员幂等入群并留系统消息；未建群（未经受理）静默跳过")
    void joinWorkOrderGroup_addsStaffIdempotently() {
        Conversation existing = group(ConversationStatus.ACTIVE);
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        when(conversationMapper.selectById(GROUP_ID)).thenReturn(existing);
        when(participantMapper.exists(any())).thenReturn(false);
        when(participantMapper.selectCount(any())).thenReturn(0L);

        assertThat(service.joinWorkOrderGroup(ORDER_ID, STAFF_ID, "李师傅")).isTrue();

        ArgumentCaptor<ConversationParticipant> captor = ArgumentCaptor.forClass(ConversationParticipant.class);
        verify(participantMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(STAFF_ID);
        assertThat(captor.getValue().getConversationId()).isEqualTo(GROUP_ID);
        verify(messageMapper).insert(ArgumentMatchers.<ConversationMessage>any());
        assertThat(existing.getLastMessage()).contains("李师傅");

        /* 已在群（改派同一人）不再重复留痕 */
        when(participantMapper.selectCount(any())).thenReturn(1L);
        assertThat(service.joinWorkOrderGroup(ORDER_ID, STAFF_ID, "李师傅")).isTrue();
        verify(messageMapper, times(1)).insert(ArgumentMatchers.<ConversationMessage>any());

        /* 无群（未经受理直接派单）：不建群、不入群 */
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertThat(service.joinWorkOrderGroup(ORDER_ID, STAFF_ID, "李师傅")).isFalse();
        verify(conversationMapper, never()).insert(ArgumentMatchers.<Conversation>any());
    }

    @Test
    @DisplayName("⑥终审关闭 → 会话回收（CLOSED + closed_at，只读留痕）；再发言 5604；历史仍可读")
    void closeWorkOrderGroup_recyclesAndBlocksNewMessages() {
        Conversation existing = group(ConversationStatus.ACTIVE);
        when(conversationMapper.selectOne(any())).thenReturn(existing);

        service.closeWorkOrderGroup(ORDER_ID);

        assertThat(existing.getStatus()).isEqualTo(ConversationStatus.CLOSED);
        assertThat(existing.getClosedAt()).isNotNull();
        verify(conversationMapper).updateById(existing);

        /* 关闭后发消息 → 5604（历史消息与会话记录均未删除） */
        when(conversationMapper.selectById(GROUP_ID)).thenReturn(existing);
        when(participantMapper.exists(any())).thenReturn(true);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            assertThatThrownBy(() -> service.sendMessage(GROUP_ID, message("关闭后还能发吗")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONVERSATION_CLOSED));
        }
        verify(messageMapper, never()).insert(ArgumentMatchers.<ConversationMessage>any());

        /* 历史只读：关闭态仍可读消息列表 */
        ConversationMessage history = new ConversationMessage();
        history.setId(1L);
        history.setConversationId(GROUP_ID);
        history.setSenderRole("SYSTEM");
        history.setSenderId(0L);
        history.setContent("工单已受理，本群用于工单沟通");
        when(messageMapper.selectList(any())).thenReturn(List.of(history));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            assertThat(service.listMessages(GROUP_ID)).hasSize(1);
        }
        verify(conversationMapper, never()).deleteById(anyLong());

        /* 重复回收幂等：已 CLOSED 不再写库 */
        service.closeWorkOrderGroup(ORDER_ID);
        verify(conversationMapper, times(1)).updateById(ArgumentMatchers.<Conversation>any());
    }

    @Test
    @DisplayName("④仅参与者可见：非参与者读消息/发言一律 403（沿用既有会话权限）")
    void nonParticipant_forbidden() {
        Conversation existing = group(ConversationStatus.ACTIVE);
        when(conversationMapper.selectById(GROUP_ID)).thenReturn(existing);
        when(participantMapper.exists(any())).thenReturn(false);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);
            assertThatThrownBy(() -> service.listMessages(GROUP_ID))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.sendMessage(GROUP_ID, message("偷看")))
                    .isInstanceOf(ForbiddenException.class);
        }
        verify(messageMapper, never()).insert(ArgumentMatchers.<ConversationMessage>any());
    }
}
