package com.community.residence.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.constant.NotificationOwnerType;
import com.community.residence.common.context.IdentityPrincipal;
import com.community.residence.conversation.entity.ConversationParticipant;
import com.community.residence.conversation.mapper.ConversationParticipantMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * DEF-075 回归（WS 侧）：`/topic/conversation/{id}` 订阅鉴权按
 * **(owner_type, user_id) 二元组**校验——resident1（resident.id=1）参与会话时，
 * 撞号的 superadmin（sys_user.id=1）订阅**必须被拒**。
 *
 * 参与者表用内存模拟：未携带 owner_type 谓词时按旧口径（仅 user_id）命中，
 * 因此一旦拦截器回归成只按 user_id 判定，本例立即失败。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("会话订阅鉴权身份空间收口（DEF-075）")
class ConversationSubscriptionInterceptorTest {

    private static final Long CONVERSATION_ID = 1L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long ADMIN_ID = 2L;

    @Mock
    private ConversationParticipantMapper participantMapper;

    @InjectMocks
    private ConversationSubscriptionInterceptor interceptor;

    private final Set<String> tupleSeats = new HashSet<>();
    private final Set<String> legacySeats = new HashSet<>();

    /** 列名 → 取值：按 `列 = #{ew.paramNameValuePairs.KEY}` 精确回查（不假设参数顺序） */
    private static final Pattern PREDICATE = Pattern.compile(
            "(conversation_id|owner_type|user_id)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}");

    /** MyBatis-Plus 列名解析需要先注册实体元数据 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationParticipant.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(participantMapper.exists(any())).thenAnswer(invocation -> {
            Map<String, Object> p = predicates(invocation.getArgument(0));
            if (!p.containsKey("owner_type")) {
                /* 旧口径（无身份空间）：撞号者会命中 ⇒ 回归即暴露 */
                return legacySeats.contains(p.get("conversation_id") + "|" + p.get("user_id"));
            }
            return tupleSeats.contains(
                    p.get("conversation_id") + "|" + p.get("owner_type") + "|" + p.get("user_id"));
        });
    }

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

    private void seat(Long conversationId, String ownerType, Long userId) {
        tupleSeats.add(conversationId + "|" + ownerType + "|" + userId);
        legacySeats.add(conversationId + "|" + userId);
    }

    private org.springframework.messaging.Message<?> subscribe(String destination, String role, Long userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                IdentityPrincipal.ofRole(role, userId), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("⑥resident1（resident.id=1）参与会话：本人订阅放行")
    void subscribe_residentParticipant_allowed() {
        seat(CONVERSATION_ID, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        assertThatCode(() -> interceptor.preSend(
                subscribe("/topic/conversation/1", "RESIDENT", RESIDENT_ID), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑥核心断言：撞号的 superadmin（sys_user.id=1）订阅只含 resident1 的会话 → 拒绝")
    void subscribe_superAdminCollidingId_rejected() {
        seat(CONVERSATION_ID, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        assertThatThrownBy(() -> interceptor.preSend(
                subscribe("/topic/conversation/1", "SUPER_ADMIN", 1L), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("非会话参与者");
    }

    @Test
    @DisplayName("⑥resident id 撞号的员工订阅其本职业空间会话：放行（未过度拦截）")
    void subscribe_userSpaceParticipant_allowed() {
        seat(CONVERSATION_ID, NotificationOwnerType.USER, ADMIN_ID);
        assertThatCode(() -> interceptor.preSend(
                subscribe("/topic/conversation/1", "ADMIN", ADMIN_ID), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⑥居民 id 与员工 id 均未登记的订阅：拒绝")
    void subscribe_stranger_rejected() {
        seat(CONVERSATION_ID, NotificationOwnerType.RESIDENT, RESIDENT_ID);
        assertThatThrownBy(() -> interceptor.preSend(
                subscribe("/topic/conversation/1", "STAFF", 7L), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("非会话参与者");
    }

    @Test
    @DisplayName("未认证（无 IdentityPrincipal）订阅：拒绝")
    void subscribe_unauthenticated_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/conversation/1");
        accessor.setLeaveMutable(true);
        accessor.setUser((Principal) () -> "anonymous");
        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("非会话 topic 订阅：不经本拦截器处理（放行）")
    void subscribe_otherTopic_passthrough() {
        assertThatCode(() -> interceptor.preSend(
                subscribe("/user/queue/notifications", "RESIDENT", RESIDENT_ID), null))
                .doesNotThrowAnyException();
    }
}
