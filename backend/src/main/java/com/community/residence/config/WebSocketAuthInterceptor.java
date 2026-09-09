package com.community.residence.config;

import com.community.residence.auth.service.TokenBlacklistService;
import com.community.residence.auth.util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * WebSocket CONNECT 帧认证拦截器。
 * 从 STOMP 头取 Authorization Bearer 令牌，复用 JwtUtil + 登出黑名单校验；
 * 通过后以 userId 为主键构造 Principal 供 convertAndSendToUser 定向推送。
 * 令牌无效时抛 MessageDeliveryException，由框架回 ERROR 帧并关闭连接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }
        String token = extractBearerToken(accessor);
        if (token == null) {
            throw new MessageDeliveryException("Unauthorized: 缺少 Authorization 头");
        }
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            throw new MessageDeliveryException("Unauthorized: 令牌无效或已过期");
        }
        String jti = claims.getId();
        if (jti != null && tokenBlacklistService.isBlacklisted(jti)) {
            throw new MessageDeliveryException("Unauthorized: 令牌已登出");
        }
        Long userId = jwtUtil.extractUserId(claims);
        String role = jwtUtil.extractRole(claims);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        accessor.setUser(authentication);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("WebSocket 认证通过：userId={}, role={}", userId, role);
        return message;
    }

    /** 取 Bearer 令牌；native header（SockJS/STOMP 客户端传法）与 Java header 双查 */
    private String extractBearerToken(StompHeaderAccessor accessor) {
        String value = accessor.getFirstNativeHeader("Authorization");
        if (!StringUtils.hasText(value)) {
            value = accessor.getFirstNativeHeader("authorization");
        }
        if (StringUtils.hasText(value) && value.startsWith("Bearer ")) {
            return value.substring("Bearer ".length());
        }
        return null;
    }
}
