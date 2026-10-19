package com.community.residence.common.messaging.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.messaging.entity.Notification;
import com.community.residence.common.messaging.mapper.NotificationMapper;
import com.community.residence.common.messaging.vo.NotificationVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知收件人身份空间分离回归（DEF-067）。
 * <p>
 * 撞号场景：resident 1（演示居民 resident1）与 sys_user 1（超管 superadmin）主键相同，
 * 而 notification 行由 (owner_type, user_id) 共同标识收件人——本用例以内存表模拟
 * 库内两类同名 id 的通知，用服务层真实构造的查询条件(resident 1 / superadmin 1)
 * 过滤该表，断言：
 * <ul>
 *   <li>发给 resident 1 的通知不出现在 superadmin 的 page/unread/pull 结果（反之亦然）；</li>
 *   <li>撞号 id 上的 markRead 越权被拒；</li>
 *   <li>生成通知时按身份空间落 owner_type 并按带前缀主体名推送。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知身份空间分离回归（DEF-067：resident 1 / sys_user 1 撞号）")
class NotificationIdentitySpaceRegressionTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();

    /** resident 1 = resident1，sys_user 1 = superadmin（两套身份空间撞号） */
    private static final Long COLLIDING_ID = 1L;

    /** MyBatis-Plus 条件占位符：按 SQL 中出现顺序取出条件值（服务层先 owner_type 后 user_id） */
    private static final Pattern PLACEHOLDER = Pattern.compile("MPGENVAL(\\d+)");

    private static final String RESIDENT_ONLY_TITLE = "首期费用支付成功";
    private static final String USER_ONLY_TITLE = "社区租约首期费用到账";

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;

    @InjectMocks
    private NotificationService notificationService;

    /** 模拟 notification 表：含 resident 1 与 sys_user 1 两条同 id、不同身份空间的通知 */
    private List<Notification> table;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "notification-test"), Notification.class);
        table = new ArrayList<>(List.of(
                row(17L, 101L, NotificationOwnerType.RESIDENT, COLLIDING_ID, RESIDENT_ONLY_TITLE),
                row(18L, 102L, NotificationOwnerType.USER, COLLIDING_ID, USER_ONLY_TITLE),
                row(19L, 103L, NotificationOwnerType.USER, 2L, "社区租约续约成功"),
                row(20L, 104L, NotificationOwnerType.RESIDENT, 2L, "续租支付成功")));

        lenient().when(redisTemplate.execute(org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.RedisScript.class), org.mockito.ArgumentMatchers.anyList(), anyString())).thenAnswer(call -> Math.max(900L, Long.parseLong(call.getArgument(2)) + 1));
        lenient().when(webSocketSessionService.isOnline(anyString(), anyLong())).thenReturn(false);
        lenient().when(notificationMapper.insert(any(Notification.class))).thenAnswer(inv -> {
            Notification inserted = inv.getArgument(0);
            inserted.setId(200L + table.size());
            table.add(inserted);
            return 1;
        });
        lenient().when(notificationMapper.selectList(any())).thenAnswer(inv -> filter(inv.getArgument(0)));
        lenient().when(notificationMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<Notification> target = inv.getArgument(0);
            List<Notification> records = filter(inv.getArgument(1));
            target.setRecords(records);
            target.setTotal(records.size());
            return target;
        });
        lenient().when(notificationMapper.update(any(), any())).thenReturn(1);
    }

    /* ---- 双向不可见：居民 1 与超管 1 ---- */

    @Test
    @DisplayName("unread：居民 1 只读到居民口径通知，超管（sys_user 1）只读到管理员口径通知")
    void unread_collidingIds_seeOwnIdentitySpaceOnly() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(RESIDENT_ONLY_TITLE);
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.SUPER_ADMIN);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(USER_ONLY_TITLE);
        }
    }

    @Test
    @DisplayName("page：同一 id 两套身份空间互不可见（双向）")
    void page_collidingIds_seeOwnIdentitySpaceOnly() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThat(notificationService.page(1, 20, null).getRecords())
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(RESIDENT_ONLY_TITLE);
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.ADMIN);
            assertThat(notificationService.page(1, 20, null).getRecords())
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(USER_ONLY_TITLE);
        }
    }

    @Test
    @DisplayName("pull：增量拉取同样按身份空间收口（双向）")
    void pull_collidingIds_seeOwnIdentitySpaceOnly() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThat(notificationService.pull(0L))
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(RESIDENT_ONLY_TITLE);
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.STAFF);
            assertThat(notificationService.pull(0L))
                    .extracting(NotificationVO::getTitle)
                    .containsExactly(USER_ONLY_TITLE);
        }
    }

    /* ---- 缺陷复现路径：支付成功通知只落发件身份空间 ---- */

    @Test
    @DisplayName("复现用例：给 resident 1 发支付成功通知后，superadmin 的 unread/page 查不到")
    void createForResident_notVisibleToCollidingSuperAdmin() {
        notificationService.createForResident(COLLIDING_ID, 1L, RESIDENT_ONLY_TITLE,
                "您的首期费用已确认到账。", "LEASE", "LEASE_PAYMENT", 3L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .contains(RESIDENT_ONLY_TITLE);
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.SUPER_ADMIN);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .doesNotContain(RESIDENT_ONLY_TITLE);
            assertThat(notificationService.page(1, 20, null).getRecords())
                    .extracting(NotificationVO::getTitle)
                    .doesNotContain(RESIDENT_ONLY_TITLE);
        }
        /* 落库行的 owner_type 必须为 RESIDENT（不允许隐式默认 USER） */
        assertThat(table).anySatisfy(row -> {
            assertThat(row.getTitle()).isEqualTo(RESIDENT_ONLY_TITLE);
            assertThat(row.getOwnerType()).isEqualTo(NotificationOwnerType.RESIDENT);
        });
    }

    @Test
    @DisplayName("反向用例：给 sys_user 1 发管理员通知后，resident 1 的 unread 查不到")
    void createForUser_notVisibleToCollidingResident() {
        notificationService.createForUser(COLLIDING_ID, 1L, USER_ONLY_TITLE,
                "租住记录 #4 的首期费用已到账。", "LEASE", "LEASE_PAYMENT", 3L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .doesNotContain(USER_ONLY_TITLE);
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.SUPER_ADMIN);
            assertThat(notificationService.unread())
                    .extracting(NotificationVO::getTitle)
                    .contains(USER_ONLY_TITLE);
        }
    }

    /* ---- 已读置位不得跨身份空间 ---- */

    @Test
    @DisplayName("markRead：居民 1 置读 sys_user 1 的通知（同 id 不同身份空间）→ 403")
    void markRead_crossIdentitySpace_forbidden() {
        when(notificationMapper.selectById(18L)).thenReturn(table.get(1));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            assertThatThrownBy(() -> notificationService.markRead(18L))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("他人通知");
        }
    }

    @Test
    @DisplayName("markRead：本人身份空间内的通知可正常置读")
    void markRead_ownIdentitySpace_allowed() {
        when(notificationMapper.selectById(17L)).thenReturn(table.get(0));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            notificationService.markRead(17L);
        }
        verify(notificationMapper).update(any(), any());
    }

    @Test
    @DisplayName("markAllRead：更新条件带 owner_type，仅命中本人身份空间")
    void markAllRead_scopesToOwnerType() {
        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            notificationService.markAllRead();
        }
        verify(notificationMapper).update(any(), captor.capture());

        Wrapper<Notification> wrapper = captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("owner_type").contains("user_id");
        assertThat(valuesOf(wrapper)).contains(NotificationOwnerType.RESIDENT, COLLIDING_ID);
    }

    /* ---- 查询条件形状（写入侧/读取侧同源身份标注） ---- */

    @Test
    @DisplayName("查询条件：居民与超管的 user_id 同为 1，但 owner_type 条件互不相同")
    void queryCondition_differsByOwnerType_forCollidingId() {
        ArgumentCaptor<Wrapper<Notification>> captor = ArgumentCaptor.forClass(Wrapper.class);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.RESIDENT);
            notificationService.unread();
        }
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            asIdentity(mocked, COLLIDING_ID, RoleConstants.SUPER_ADMIN);
            notificationService.unread();
        }
        verify(notificationMapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        List<Wrapper<Notification>> wrappers = captor.getAllValues();
        Wrapper<Notification> residentWrapper = wrappers.get(0);
        Wrapper<Notification> adminWrapper = wrappers.get(1);

        assertThat(residentWrapper.getSqlSegment()).contains("owner_type").contains("user_id");
        assertThat(identityConditions(residentWrapper))
                .containsExactly(NotificationOwnerType.RESIDENT, COLLIDING_ID);
        assertThat(identityConditions(adminWrapper))
                .containsExactly(NotificationOwnerType.USER, COLLIDING_ID);
    }

    @Test
    @DisplayName("WebSocket 主体名：撞号 id 的居民与员工目的地分别为 R:1 与 U:1")
    void pushSubject_collidingId_usesDistinctDestination() {
        when(webSocketSessionService.isOnline(NotificationOwnerType.RESIDENT, COLLIDING_ID)).thenReturn(true);
        when(webSocketSessionService.isOnline(NotificationOwnerType.USER, COLLIDING_ID)).thenReturn(true);

        notificationService.createForResident(COLLIDING_ID, 1L, "R", "C", "TYPE", "SRC", 1L);
        notificationService.createForUser(COLLIDING_ID, 1L, "U", "C", "TYPE", "SRC", 1L);

        verify(messagingTemplate).convertAndSendToUser(
                org.mockito.ArgumentMatchers.eq("R:1"), anyString(), any());
        verify(messagingTemplate).convertAndSendToUser(
                org.mockito.ArgumentMatchers.eq("U:1"), anyString(), any());
    }

    /* ---- 辅助：内存表按服务层真实构造的条件过滤 ---- */

    private List<Notification> filter(Wrapper<Notification> wrapper) {
        List<Object> conditions = identityConditions(wrapper);
        String ownerType = (String) conditions.get(0);
        Long userId = (Long) conditions.get(1);
        return table.stream()
                .filter(row -> ownerType.equals(row.getOwnerType()) && userId.equals(row.getUserId()))
                .toList();
    }

    /** 条件值按 SQL 中占位符出现顺序取出（服务层固定先 owner_type 后 user_id） */
    private List<Object> identityConditions(Wrapper<Notification> wrapper) {
        Map<String, Object> params = abstractWrapper(wrapper).getParamNameValuePairs();
        Matcher matcher = PLACEHOLDER.matcher(wrapper.getSqlSegment());
        List<Object> conditions = new ArrayList<>();
        while (matcher.find() && conditions.size() < 2) {
            conditions.add(params.get("MPGENVAL" + matcher.group(1)));
        }
        return conditions;
    }

    private List<Object> valuesOf(Wrapper<Notification> wrapper) {
        return new ArrayList<>(abstractWrapper(wrapper).getParamNameValuePairs().values());
    }

    private AbstractWrapper<?, ?, ?> abstractWrapper(Wrapper<Notification> wrapper) {
        return (AbstractWrapper<?, ?, ?>) wrapper;
    }

    private void asIdentity(MockedStatic<SecurityUtils> mocked, Long userId, String role) {
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new UserContext(userId, role.toLowerCase(), role, Set.of(1L)));
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
    }

    private Notification row(Long id, Long seq, String ownerType, Long userId, String title) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setSeq(seq);
        notification.setOwnerType(ownerType);
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(title);
        notification.setType("LEASE");
        notification.setSourceType("LEASE_PAYMENT");
        notification.setSourceId(3L);
        notification.setCommunityId(1L);
        notification.setIsRead(0);
        return notification;
    }
}
