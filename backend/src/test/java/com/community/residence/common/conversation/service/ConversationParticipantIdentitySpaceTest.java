package com.community.residence.common.conversation.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.conversation.dto.SendMessageDTO;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.entity.ConversationMessage;
import com.community.residence.common.conversation.entity.ConversationParticipant;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.mapper.ConversationMessageMapper;
import com.community.residence.common.conversation.mapper.ConversationParticipantMapper;
import com.community.residence.common.conversation.vo.ConversationParticipantVO;
import com.community.residence.common.conversation.vo.ConversationVO;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-075 回归：会话参与者**身份空间收口**（(owner_type, user_id) 二元组）。
 *
 * 缺陷背景：`conversation_participant.user_id` 是无身份空间列的多态引用
 * （居民=resident.id / STAFF·ADMIN·SUPER_ADMIN=sys_user.id），两表自增序列独立
 * 且主键可撞号（实测 resident 1 vs sys_user 1）。原实现判定 / 展示 / 未读 / 推送
 * 只按 user_id ⇒ 撞号的另一身份空间主体越权进入他人会话。
 *
 * 本类用**内存参与者表 + 按谓词列名解析参数**的方式模拟真表语义：
 * 若被测代码只按 user_id 判定（即缺陷回归），模拟表会按旧口径命中，
 * 测试随即失败——因此断言的是行为而非调用次数。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("会话参与者身份空间收口（DEF-075）")
class ConversationParticipantIdentitySpaceTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    private static final Long DIRECT_CONV = 1L;      /* 直通会话：resident1 + admin1 */
    private static final Long WORK_ORDER_CONV = 2L;  /* 工单临时群聊：resident1 + admin1 + staff1 */
    private static final Long COLLIDE_CONV = 3L;     /* 仅 USER 身份空间 id=1（超级管理员） */
    private static final Long RESIDENT_ID = 1L;      /* 撞号样本：resident.id=1（resident1） */
    private static final Long SUPER_ADMIN_ID = 1L;   /* 撞号样本：sys_user.id=1（superadmin） */
    private static final Long ADMIN_ID = 2L;
    private static final Long STAFF_ID = 3L;

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

    /** 内存参与者表：conversationId | owner_type | user_id（完整身份二元组） */
    private final List<ConversationParticipant> rows = new ArrayList<>();
    private long nextId = 100L;

    /** MyBatis-Plus 列名解析需要先注册实体元数据（否则 getSqlSegment 抛 lambda cache 异常） */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationParticipant.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(webSocketSessionService.isResidentOnline(anyLong())).thenReturn(false);
        lenient().when(webSocketSessionService.isUserOnline(anyLong())).thenReturn(false);
        stubParticipantTable();
    }

    /* ---------------- 内存表：按 (conversation_id, owner_type, user_id) 谓词模拟 ---------------- */

    /** 列名 → 取值：按 SQL 片段中的 `列 = #{ew.paramNameValuePairs.KEY}` 精确回查，避免顺序假设 */
    private static final Pattern PREDICATE = Pattern.compile(
            "(conversation_id|owner_type|user_id)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

    private Map<String, Object> predicates(LambdaQueryWrapper<ConversationParticipant> wrapper) {
        String sql = wrapper.getSqlSegment();
        Map<String, Object> params = wrapper.getParamNameValuePairs();
        Map<String, Object> result = new HashMap<>();
        Matcher matcher = PREDICATE.matcher(sql);
        while (matcher.find()) {
            result.put(matcher.group(1), params.get(matcher.group(2)));
        }
        return result;
    }

    private List<ConversationParticipant> select(LambdaQueryWrapper<ConversationParticipant> wrapper) {
        Map<String, Object> p = predicates(wrapper);
        return rows.stream().filter(row -> {
            if (p.containsKey("conversation_id") && !row.getConversationId().equals(p.get("conversation_id"))) {
                return false;
            }
            if (p.containsKey("user_id") && !row.getUserId().equals(p.get("user_id"))) {
                return false;
            }
            /* 未带 owner_type 谓词 = 旧口径（只按 user_id），仍按旧口径命中 ⇒
               一旦代码回归成只按 user_id 判定，越权断言立即失败 */
            return !p.containsKey("owner_type") || row.getOwnerType().equals(p.get("owner_type"));
        }).toList();
    }

    private void stubParticipantTable() {
        lenient().when(participantMapper.exists(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<ConversationParticipant> wrapper = inv.getArgument(0);
            return !select(wrapper).isEmpty();
        });
        lenient().when(participantMapper.selectCount(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<ConversationParticipant> wrapper = inv.getArgument(0);
            return (long) select(wrapper).size();
        });
        lenient().when(participantMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<ConversationParticipant> wrapper = inv.getArgument(0);
            List<ConversationParticipant> found = select(wrapper);
            return found.isEmpty() ? null : found.get(0);
        });
        lenient().when(participantMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<ConversationParticipant> wrapper = inv.getArgument(0);
            return select(wrapper);
        });
        lenient().when(participantMapper.insert(any(ConversationParticipant.class))).thenAnswer(inv -> {
            ConversationParticipant row = inv.getArgument(0);
            row.setId(nextId++);
            rows.add(row);
            return 1;
        });
        lenient().when(participantMapper.updateById(any(ConversationParticipant.class))).thenReturn(1);
    }

    /* ---------------- 夹具 ---------------- */

    private void seat(Long conversationId, String ownerType, Long userId) {
        ConversationParticipant row = new ConversationParticipant();
        row.setId(nextId++);
        row.setConversationId(conversationId);
        row.setOwnerType(ownerType);
        row.setUserId(userId);
        row.setLastReadMessageId(0L);
        rows.add(row);
    }

    private Conversation conversation(Long id, String type, Long communityId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setType(type);
        conversation.setTitle(type);
        conversation.setCommunityId(communityId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        return conversation;
    }

    private MockedStatic<SecurityUtils> loginAs(String role, Long userId) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        UserContext context = new UserContext(userId, role.toLowerCase(), role, java.util.Set.of());
        mocked.when(SecurityUtils::getUser).thenReturn(context);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT"))
                .thenReturn(com.community.residence.common.security.constant.RoleConstants.RESIDENT.equals(role));
        return mocked;
    }

    private SendMessageDTO message(String content) {
        SendMessageDTO dto = new SendMessageDTO();
        dto.setContent(content);
        return dto;
    }

    /* ---------------- ① 核心断言：撞号主体必须被拒 ---------------- */

    @Test
    @DisplayName("①superadmin(sys_user.id=1) 访问只含 resident1(resident.id=1) 的会话 → 403（读/发/已读全拒）")
    void superAdminCollidingId_forbidden() {
        seat(DIRECT_CONV, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        seat(DIRECT_CONV, NotificationOwnerType.USER, ADMIN_ID);
        when(conversationMapper.selectById(DIRECT_CONV))
                .thenReturn(conversation(DIRECT_CONV, ConversationType.DIRECT, 1L));

        try (MockedStatic<SecurityUtils> mocked = loginAs("SUPER_ADMIN", SUPER_ADMIN_ID)) {
            assertThatThrownBy(() -> service.getById(DIRECT_CONV))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("非会话参与者");
            assertThatThrownBy(() -> service.listMessages(DIRECT_CONV))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.sendMessage(DIRECT_CONV, message("越权发言")))
                    .isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.markRead(DIRECT_CONV, 1L))
                    .isInstanceOf(ForbiddenException.class);
        }
        verify(messageMapper, never()).insert(any(ConversationMessage.class));
    }

    @Test
    @DisplayName("⑤同一 user_id 在两个身份空间互不可见；本职空间访问不受影响")
    void collidingId_mutuallyInvisible() {
        /* 会话 3 只有 USER:1（超级管理员）参与；resident 1 不得进入 */
        seat(COLLIDE_CONV, NotificationOwnerType.USER, SUPER_ADMIN_ID);
        when(conversationMapper.selectById(COLLIDE_CONV))
                .thenReturn(conversation(COLLIDE_CONV, ConversationType.DIRECT, 1L));
        try (MockedStatic<SecurityUtils> mocked = loginAs("RESIDENT", RESIDENT_ID)) {
            assertThatThrownBy(() -> service.getById(COLLIDE_CONV))
                    .isInstanceOf(ForbiddenException.class);
        }
        /* 同一 id 在其本职空间仍可见（未过度拦截） */
        try (MockedStatic<SecurityUtils> mocked = loginAs("SUPER_ADMIN", SUPER_ADMIN_ID)) {
            assertThat(service.getById(COLLIDE_CONV).getId()).isEqualTo(COLLIDE_CONV);
        }
        /* 反向：会话 1 只有 RESIDENT:1 参与；USER:1 不得进入，居民 1 正常进入 */
        seat(DIRECT_CONV, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        when(conversationMapper.selectById(DIRECT_CONV))
                .thenReturn(conversation(DIRECT_CONV, ConversationType.DIRECT, 1L));
        try (MockedStatic<SecurityUtils> mocked = loginAs("SUPER_ADMIN", SUPER_ADMIN_ID)) {
            assertThatThrownBy(() -> service.listMessages(DIRECT_CONV))
                    .isInstanceOf(ForbiddenException.class);
        }
        try (MockedStatic<SecurityUtils> mocked = loginAs("RESIDENT", RESIDENT_ID)) {
            assertThat(service.listMessages(DIRECT_CONV)).isEmpty();
        }
    }

    /* ---------------- ②③ 本职空间可读可发 + 展示不再跨空间 ---------------- */

    @Test
    @DisplayName("②resident1 本人可读可发；成员展示按其身份空间取居民姓名（不再显示成超级管理员）")
    void residentOwner_allowedAndProfileResolvesOwnSpace() {
        seat(DIRECT_CONV, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        seat(DIRECT_CONV, NotificationOwnerType.USER, ADMIN_ID);
        when(conversationMapper.selectById(DIRECT_CONV))
                .thenReturn(conversation(DIRECT_CONV, ConversationType.DIRECT, 1L));

        Resident resident1 = new Resident();
        resident1.setId(RESIDENT_ID);
        resident1.setRealName("张伟");
        when(residentMapper.selectBatchIds(any())).thenReturn(List.of(resident1));
        SysUser systemUser1 = new SysUser();
        systemUser1.setId(SUPER_ADMIN_ID);
        systemUser1.setRealName("超级管理员");
        systemUser1.setRole("SUPER_ADMIN");
        SysUser admin1 = new SysUser();
        admin1.setId(ADMIN_ID);
        admin1.setRealName("小区管理员");
        admin1.setRole("ADMIN");
        /* 两表都存在 id=1（撞号），断言的正是"不得跨空间取 sys_user" */
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of(systemUser1, admin1));

        try (MockedStatic<SecurityUtils> mocked = loginAs("RESIDENT", RESIDENT_ID)) {
            ConversationVO vo = service.getById(DIRECT_CONV);
            assertThat(vo.getParticipants()).hasSize(2);
            ConversationParticipantVO self = vo.getParticipants().stream()
                    .filter(p -> RESIDENT_ID.equals(p.getUserId())
                            && NotificationOwnerType.RESIDENT.equals(p.getOwnerType()))
                    .findFirst().orElseThrow();
            assertThat(self.getName()).isEqualTo("张伟");
            assertThat(self.getRole()).isEqualTo("RESIDENT");
            ConversationParticipantVO admin = vo.getParticipants().stream()
                    .filter(p -> NotificationOwnerType.USER.equals(p.getOwnerType()))
                    .findFirst().orElseThrow();
            assertThat(admin.getName()).isEqualTo("小区管理员");
            /* 展示里不得出现"撞号者"超级管理员 */
            assertThat(vo.getParticipants()).extracting(ConversationParticipantVO::getName)
                    .doesNotContain("超级管理员");

            service.sendMessage(DIRECT_CONV, message("居民发言"));
        }
        ArgumentCaptor<ConversationMessage> captor = ArgumentCaptor.forClass(ConversationMessage.class);
        verify(messageMapper).insert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isEqualTo(DIRECT_CONV);
    }

    @Test
    @DisplayName("③admin1 / staff1 在其参与的会话内可读（三类会话同口径）")
    void staffAndAdmin_allowedInOwnGroups() {
        seat(WORK_ORDER_CONV, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        seat(WORK_ORDER_CONV, NotificationOwnerType.USER, ADMIN_ID);
        seat(WORK_ORDER_CONV, NotificationOwnerType.USER, STAFF_ID);
        when(conversationMapper.selectById(WORK_ORDER_CONV))
                .thenReturn(conversation(WORK_ORDER_CONV, ConversationType.WORK_ORDER_GROUP, 1L));

        try (MockedStatic<SecurityUtils> mocked = loginAs("STAFF", STAFF_ID)) {
            assertThat(service.getById(WORK_ORDER_CONV).getId()).isEqualTo(WORK_ORDER_CONV);
        }
        try (MockedStatic<SecurityUtils> mocked = loginAs("ADMIN", ADMIN_ID)) {
            assertThat(service.listMessages(WORK_ORDER_CONV)).isEmpty();
        }
        try (MockedStatic<SecurityUtils> mocked = loginAs("RESIDENT", RESIDENT_ID)) {
            assertThat(service.getById(WORK_ORDER_CONV).getId()).isEqualTo(WORK_ORDER_CONV);
        }
    }

    @Test
    @DisplayName("④三类会话建群入群同口径：居民入 RESIDENT、管理员/带看人/服务人员入 USER")
    void allGroupTypes_tagOwnerType() {
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(inv -> {
            Conversation created = inv.getArgument(0);
            created.setId(nextId++);   /* 三张会话各自唯一，避免误命中幂等分支 */
            return 1;
        });
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setCommunityId(1L);
        binding.setAdminId(9L);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        SysUser admin = new SysUser();
        admin.setId(9L);
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        admin.setRealName("管理员");
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.createViewingGroup(11L, 1L, RESIDENT_ID, "测试房源");
        service.createLeaseGroup(12L, 1L, RESIDENT_ID, "租约沟通");
        service.createWorkOrderGroup(13L, 1L, RESIDENT_ID, 9L, "WO202609220003");

        assertThat(rows).extracting(r -> r.getOwnerType() + ":" + r.getUserId())
                .contains(NotificationOwnerType.RESIDENT + ":" + RESIDENT_ID,
                        NotificationOwnerType.USER + ":9");
        /* 三类会话各自的 3 组入群：居民 1 行 + 管理员 1 行（每类各一组） */
        assertThat(rows.stream()
                .filter(r -> NotificationOwnerType.RESIDENT.equals(r.getOwnerType())).count())
                .isEqualTo(3);
        assertThat(rows.stream()
                .filter(r -> NotificationOwnerType.USER.equals(r.getOwnerType())).count())
                .isEqualTo(3);

        /* 兼容入口（带看人分配遗留签名）按 USER 语义收口 */
        service.addParticipant(WORK_ORDER_CONV, STAFF_ID);
        assertThat(rows).extracting(r -> r.getOwnerType() + ":" + r.getUserId())
                .contains(NotificationOwnerType.USER + ":" + STAFF_ID);
    }

    @Test
    @DisplayName("推送在线判定按各自身份空间（居民行查居民在线表，员工行查用户在线表）")
    void pushOnlineCheck_isPerIdentitySpace() {
        seat(DIRECT_CONV, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        seat(DIRECT_CONV, NotificationOwnerType.USER, ADMIN_ID);
        when(conversationMapper.selectById(DIRECT_CONV))
                .thenReturn(conversation(DIRECT_CONV, ConversationType.DIRECT, 1L));

        try (MockedStatic<SecurityUtils> mocked = loginAs("RESIDENT", RESIDENT_ID)) {
            service.sendMessage(DIRECT_CONV, message("居民发言"));
        }
        verify(webSocketSessionService).isResidentOnline(RESIDENT_ID);
        verify(webSocketSessionService).isUserOnline(ADMIN_ID);
        verify(webSocketSessionService, never()).isUserOnline(RESIDENT_ID);
        verify(webSocketSessionService, never()).isResidentOnline(ADMIN_ID);
    }
}
