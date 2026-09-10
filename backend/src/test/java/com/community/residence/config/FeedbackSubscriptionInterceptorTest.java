package com.community.residence.config;

import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.mapper.FeedbackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.Principal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 反馈会话订阅鉴权测试（R30）：居民本人/越权居民/管理员绑定社区/超管/无权限管理员/STAFF */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackSubscriptionInterceptor 单元测试")
class FeedbackSubscriptionInterceptorTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;

    @InjectMocks
    private FeedbackSubscriptionInterceptor interceptor;

    private Feedback feedback;

    @BeforeEach
    void setUp() {
        feedback = new Feedback();
        feedback.setId(1L);
        feedback.setResidentId(10L);
        feedback.setCommunityId(3L);
        lenient().when(feedbackMapper.selectById(1L)).thenReturn(feedback);
    }

    private org.springframework.messaging.Message<?> subscribeMessage(String destination, String role, Long userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("居民提交人订阅本人反馈会话：放行")
    void subscribe_residentOwner_allowed() {
        assertThatCode(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "RESIDENT", 10L), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("越权居民订阅他人反馈会话：拒绝")
    void subscribe_residentOther_rejected() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "RESIDENT", 99L), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("非反馈会话参与者");
    }

    @Test
    @DisplayName("绑定社区的管理员订阅：放行")
    void subscribe_adminBoundCommunity_allowed() {
        when(sysAdminCommunityMapper.selectCount(any())).thenReturn(1L);
        assertThatCode(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "ADMIN", 5L), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未绑定社区的管理员订阅：拒绝")
    void subscribe_adminUnboundCommunity_rejected() {
        when(sysAdminCommunityMapper.selectCount(any())).thenReturn(0L);
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "ADMIN", 5L), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("非反馈会话参与者");
    }

    @Test
    @DisplayName("超管订阅：放行（全局视角）")
    void subscribe_superAdmin_allowed() {
        assertThatCode(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "SUPER_ADMIN", 1L), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("STAFF 订阅反馈会话：拒绝（§6.1 会话权限仅 RESIDENT/ADMIN）")
    void subscribe_staff_rejected() {
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/1", "STAFF", 7L), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("非反馈会话参与者");
    }

    @Test
    @DisplayName("反馈不存在：拒绝（不泄露存在性）")
    void subscribe_missingFeedback_rejected() {
        lenient().when(feedbackMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/feedback/999", "RESIDENT", 10L), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("未认证（无 Principal）订阅：拒绝")
    void subscribe_unauthenticated_rejected() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/feedback/1");
        accessor.setLeaveMutable(true);
        accessor.setUser((Principal) () -> "anonymous");
        org.springframework.messaging.Message<?> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("非反馈 topic 订阅：不经本拦截器处理（放行）")
    void subscribe_otherTopic_passthrough() {
        assertThatCode(() -> interceptor.preSend(
                subscribeMessage("/user/queue/notifications", "RESIDENT", 10L), null))
                .doesNotThrowAnyException();
    }
}
