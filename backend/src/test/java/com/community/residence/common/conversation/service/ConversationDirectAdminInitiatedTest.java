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
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.entity.ResidenceRelation;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 管理端开启会话回归（DEF-076）：
 * 管理端（ADMIN/SUPER_ADMIN）可代居民发起直通会话（`POST /conversations/direct` + `residentId`），
 * 从而补齐「管理端无法开启会话」的后端缺口；居民本人入口行为不变，且两入口幂等键一致。
 * 参与者身份空间（DEF-075）：居民入 RESIDENT 空间、管理员入 USER 空间。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("直通会话管理端发起（DEF-076）")
class ConversationDirectAdminInitiatedTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    private static final Long COMMUNITY_ID = 1L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long CONVERSATION_ID = 31L;

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

    private final List<ConversationParticipant> rows = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationParticipant.class);
    }

    @BeforeEach
    void setUp() {
        rows.clear();
        lenient().when(conversationMapper.insert(ArgumentMatchers.<Conversation>any())).thenAnswer(inv -> {
            Conversation created = inv.getArgument(0);
            created.setId(CONVERSATION_ID);
            return 1;
        });
        lenient().when(participantMapper.insert(any(ConversationParticipant.class))).thenAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(participantMapper.selectList(any())).thenAnswer(inv -> select(inv.getArgument(0)));
        lenient().when(participantMapper.selectCount(any())).thenAnswer(inv -> (long) select(inv.getArgument(0)).size());
        lenient().when(participantMapper.exists(any())).thenAnswer(inv -> !select(inv.getArgument(0)).isEmpty());
        lenient().when(conversationMapper.selectOne(any())).thenReturn(null);
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

    private static final Pattern PREDICATE = Pattern.compile(
            "(conversation_id|owner_type|user_id)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

    private List<ConversationParticipant> select(LambdaQueryWrapper<ConversationParticipant> wrapper) {
        String sql = wrapper.getSqlSegment();
        var params = wrapper.getParamNameValuePairs();
        String ownerType = null;
        Long userId = null;
        Matcher m = PREDICATE.matcher(sql);
        while (m.find()) {
            Object value = params.get(m.group(2));
            if ("owner_type".equals(m.group(1))) {
                ownerType = (String) value;
            } else if ("user_id".equals(m.group(1))) {
                userId = (Long) value;
            }
        }
        final String space = ownerType;
        final Long uid = userId;
        return rows.stream()
                .filter(r -> space == null || r.getOwnerType().equals(space))
                .filter(r -> uid == null || r.getUserId().equals(uid))
                .toList();
    }

    private void seat(Long conversationId, String ownerType, Long userId) {
        ConversationParticipant row = new ConversationParticipant();
        row.setConversationId(conversationId);
        row.setOwnerType(ownerType);
        row.setUserId(userId);
        row.setLastReadMessageId(0L);
        rows.add(row);
    }

    private MockedStatic<SecurityUtils> login(String role, Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new UserContext(userId, role.toLowerCase(), role, Set.of(COMMUNITY_ID)));
        mocked.when(() -> SecurityUtils.hasRole(any()))
                .thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT))
                .thenReturn(RoleConstants.RESIDENT.equals(role));
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN))
                .thenReturn(RoleConstants.ADMIN.equals(role));
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN))
                .thenReturn(RoleConstants.SUPER_ADMIN.equals(role));
        return mocked;
    }

    private Resident resident() {
        Resident resident = new Resident();
        resident.setId(RESIDENT_ID);
        resident.setRealName("张居民");
        return resident;
    }

    private void stubMembership() {
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);
        when(leaseRecordMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("ADMIN 代居民发起 → 建会话，参与者 = 居民(RESIDENT) + 绑定管理员(USER)")
    void adminInitiated_createsConversationForResident() {
        stubMembership();
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident());

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.ADMIN, ADMIN_ID)) {
            var vo = service.openDirect(COMMUNITY_ID, RESIDENT_ID);

            assertThat(vo.getId()).isEqualTo(CONVERSATION_ID);
            assertThat(rows).extracting(r -> r.getOwnerType() + ":" + r.getUserId())
                    .containsExactlyInAnyOrder(
                            NotificationOwnerType.RESIDENT + ":" + RESIDENT_ID,
                            NotificationOwnerType.USER + ":" + ADMIN_ID);
        }
    }

    @Test
    @DisplayName("ADMIN 未指定居民 → 5001 参数错误（不得建出无对象的会话）")
    void adminInitiated_withoutResidentId_rejected() {
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.ADMIN, ADMIN_ID)) {
            assertThatThrownBy(() -> service.openDirect(COMMUNITY_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("请指定直通会话的居民");
        }
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("ADMIN 指定不存在的居民 → 404 资源不存在")
    void adminInitiated_residentNotFound() {
        when(residentMapper.selectById(999L)).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.ADMIN, ADMIN_ID)) {
            assertThatThrownBy(() -> service.openDirect(COMMUNITY_ID, 999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("居民本人发起：省略 residentId → 取当前登录居民（既有入口行为不变）")
    void residentSelfInitiated_usesCurrentUser() {
        stubMembership();
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.RESIDENT, RESIDENT_ID)) {
            service.openDirect(COMMUNITY_ID, null);
            assertThat(rows).extracting(r -> r.getOwnerType() + ":" + r.getUserId())
                    .contains(NotificationOwnerType.RESIDENT + ":" + RESIDENT_ID);
        }
    }

    @Test
    @DisplayName("居民传他人 residentId → 403（不得代他人建会话）")
    void residentInitiated_forOtherResident_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.RESIDENT, RESIDENT_ID)) {
            assertThatThrownBy(() -> service.openDirect(COMMUNITY_ID, 99L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅可为本人");
        }
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("幂等：管理方先建、居民后发起命中同一条会话（不重复建群）")
    void adminThenResident_idempotent() {
        stubMembership();
        when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident());
        Conversation existing = new Conversation();
        existing.setId(CONVERSATION_ID);
        existing.setType("DIRECT");
        existing.setCommunityId(COMMUNITY_ID);
        existing.setStatus("ACTIVE");
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        seat(CONVERSATION_ID, NotificationOwnerType.RESIDENT, RESIDENT_ID);

        try (MockedStatic<SecurityUtils> mocked = login(RoleConstants.RESIDENT, RESIDENT_ID)) {
            var vo = service.openDirect(COMMUNITY_ID, null);
            assertThat(vo.getId()).isEqualTo(CONVERSATION_ID);
            assertThat(rows).hasSize(1);
        }
    }
}
