package com.community.residence.common.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 建/取租约沟通会话回归（DEF-076 第二入口）：
 * 管理端可按 leaseId 幂等建/取 LEASE_GROUP——存量/旁路租约缺会话时补建，
 * 并把 conversation_id 回写租约（同时满足承约前置 DEF-077「会话已建立」）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("建/取租约沟通会话（DEF-076）")
class ConversationLeaseGroupEnsureTest {

    private static final Long LEASE_ID = 3L;
    private static final Long COMMUNITY_ID = 2L;
    private static final Long RESIDENT_ID = 2L;
    private static final Long ADMIN_ID = 2L;
    private static final Long CONVERSATION_ID = 55L;

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
    private ConversationService conversationService;

    private final List<ConversationParticipant> rows = new ArrayList<>();
    private LeaseRecord lease;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationParticipant.class);
        /* 回链条件更新的 LambdaUpdateWrapper.set 需 LeaseRecord 元数据（DEF-119 适配） */
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LeaseRecord.class);
    }

    @BeforeEach
    void setUp() {
        rows.clear();
        lease = new LeaseRecord();
        lease.setId(LEASE_ID);
        lease.setCommunityId(COMMUNITY_ID);
        lease.setTenantId(RESIDENT_ID);
        when(leaseRecordMapper.selectById(LEASE_ID)).thenReturn(lease);
        when(conversationMapper.insert(ArgumentMatchers.<Conversation>any())).thenAnswer(inv -> {
            Conversation created = inv.getArgument(0);
            created.setId(CONVERSATION_ID);
            return 1;
        });
        when(participantMapper.insert(any(ConversationParticipant.class))).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(participantMapper.exists(any())).thenReturn(false);
        lenient().when(participantMapper.selectList(any())).thenReturn(List.of());
        lenient().when(webSocketSessionService.isResidentOnline(anyLong())).thenReturn(false);
        lenient().when(webSocketSessionService.isUserOnline(anyLong())).thenReturn(false);

        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setCommunityId(COMMUNITY_ID);
        binding.setAdminId(ADMIN_ID);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        SysUser admin = new SysUser();
        admin.setId(ADMIN_ID);
        admin.setRole(RoleConstants.ADMIN);
        admin.setStatus("ACTIVE");
        admin.setRealName("社区管理员");
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));
    }

    private MockedStatic<SecurityUtils> loginAsAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(ADMIN_ID);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new UserContext(ADMIN_ID, "admin1", RoleConstants.ADMIN, Set.of(COMMUNITY_ID)));
        mocked.when(() -> SecurityUtils.hasRole(any())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(true);
        return mocked;
    }

    @Test
    @DisplayName("存量租约缺会话（conversation_id 为空）→ 建群 + 回写 conversation_id + 居民/管理员入群")
    void ensure_createsGroupAndBackfillsLink() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            var vo = conversationService.ensureLeaseGroup(LEASE_ID);

            assertThat(vo.getId()).isEqualTo(CONVERSATION_ID);
            /* 回链补写：承约前置（DEF-077）据此判定「会话已建立」 */
            assertThat(lease.getConversationId()).isEqualTo(CONVERSATION_ID);
            verify(leaseRecordMapper).update(isNull(), any());
            assertThat(rows).extracting(r -> r.getOwnerType() + ":" + r.getUserId())
                    .containsExactlyInAnyOrder(
                            NotificationOwnerType.RESIDENT + ":" + RESIDENT_ID,
                            NotificationOwnerType.USER + ":" + ADMIN_ID);
        }
    }

    @Test
    @DisplayName("幂等：同租约已有 LEASE_GROUP → 返回既有会话，不重复建群（仍补写回链）")
    void ensure_idempotentWhenGroupExists() {
        Conversation existing = new Conversation();
        existing.setId(CONVERSATION_ID);
        existing.setType("LEASE_GROUP");
        existing.setCommunityId(COMMUNITY_ID);
        existing.setRelatedId(LEASE_ID);
        existing.setStatus("ACTIVE");
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(existing);

        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            var vo = conversationService.ensureLeaseGroup(LEASE_ID);

            assertThat(vo.getId()).isEqualTo(CONVERSATION_ID);
            verify(conversationMapper, never()).insert(any(Conversation.class));
            assertThat(lease.getConversationId()).isEqualTo(CONVERSATION_ID);
        }
    }

    @Test
    @DisplayName("已回链且会话存在：不重复 updateById（幂等无副作用）")
    void ensure_alreadyLinked_noUpdate() {
        lease.setConversationId(CONVERSATION_ID);
        Conversation existing = new Conversation();
        existing.setId(CONVERSATION_ID);
        existing.setType("LEASE_GROUP");
        existing.setCommunityId(COMMUNITY_ID);
        existing.setRelatedId(LEASE_ID);
        existing.setStatus("ACTIVE");
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        when(conversationMapper.selectById(CONVERSATION_ID)).thenReturn(existing);

        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            conversationService.ensureLeaseGroup(LEASE_ID);
            verify(leaseRecordMapper, never()).updateById(any(LeaseRecord.class));
        }
    }

    @Test
    @DisplayName("租约不存在 → 404")
    void ensure_leaseNotFound() {
        when(leaseRecordMapper.selectById(999L)).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            assertThatThrownBy(() -> conversationService.ensureLeaseGroup(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("租约无居民账号（历史脏数据）→ 5001 业务失败而非静默建空群")
    void ensure_leaseWithoutResident_rejected() {
        lease.setTenantId(null);
        try (MockedStatic<SecurityUtils> mocked = loginAsAdmin()) {
            assertThatThrownBy(() -> conversationService.ensureLeaseGroup(LEASE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
