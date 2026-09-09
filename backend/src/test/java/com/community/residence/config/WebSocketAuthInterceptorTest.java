package com.community.residence.config;

import com.community.residence.auth.service.TokenBlacklistService;
import com.community.residence.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** WebSocket CONNECT 认证拦截器测试：令牌校验、黑名单拒绝、Principal 注入 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketAuthInterceptor 单元测试")
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private Claims claims;

    @InjectMocks
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        lenient().when(claims.getId()).thenReturn("jti-1");
        lenient().when(claims.getSubject()).thenReturn("7");
        lenient().when(claims.get("role", String.class)).thenReturn("RESIDENT");
    }

    private org.springframework.messaging.Message<?> connectMessage(String authHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        /* CONNECT 帧在真实链路中可变，保持可变以允许拦截器注入 Principal */
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("有效令牌：Principal 注入 userId，消息放行")
    void preSend_validToken_setsPrincipal() {
        when(jwtUtil.parseToken("token-1")).thenReturn(claims);
        when(jwtUtil.extractUserId(claims)).thenReturn(7L);
        when(jwtUtil.extractRole(claims)).thenReturn("RESIDENT");
        when(tokenBlacklistService.isBlacklisted("jti-1")).thenReturn(false);

        var message = interceptor.preSend(connectMessage("Bearer token-1"), null);

        StompHeaderAccessor result = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        assertThat(result.getUser()).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        assertThat(result.getUser().getName()).isEqualTo("7");
    }

    @Test
    @DisplayName("缺少 Authorization 头：CONNECT 被拒")
    void preSend_missingHeader_rejected() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("令牌无效/过期：CONNECT 被拒")
    void preSend_invalidToken_rejected() {
        when(jwtUtil.parseToken("bad")).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer bad"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("已登出令牌（黑名单）：CONNECT 被拒")
    void preSend_blacklistedToken_rejected() {
        when(jwtUtil.parseToken("logged-out")).thenReturn(claims);
        when(tokenBlacklistService.isBlacklisted("jti-1")).thenReturn(true);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("Bearer logged-out"), null))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    @DisplayName("非 CONNECT 帧（如 SUBSCRIBE）：直接放行不校验")
    void preSend_nonConnectCommand_passesThrough() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        var result = interceptor.preSend(message, null);

        assertThat(result).isSameAs(message);
    }
}
