package com.community.residence.messaging;

import com.community.residence.messaging.service.WebSocketSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WebSocket 连接生命周期监听：上线/下线同步 Redis 会话注册表
 * （ws:session:{userId}），供通知推送前判断在线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final WebSocketSessionService webSocketSessionService;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Long userId = resolveUserId(event.getMessage().getHeaders());
        if (userId != null) {
            webSocketSessionService.register(userId);
            log.debug("WebSocket 上线：userId={}", userId);
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        Long userId = resolveUserId(event.getMessage().getHeaders());
        if (userId != null) {
            webSocketSessionService.deregister(userId);
            log.debug("WebSocket 下线：userId={}", userId);
        }
    }

    /** Principal 由 CONNECT 认证拦截器注入（name = userId） */
    private Long resolveUserId(org.springframework.messaging.MessageHeaders headers) {
        Object raw = headers.get(SimpMessageHeaderAccessor.USER_HEADER);
        if (raw instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        if (raw instanceof java.security.Principal principal) {
            try {
                return Long.valueOf(principal.getName());
            } catch (NumberFormatException ignored) {
                // 非 userId 主键的 Principal（不应出现），忽略
            }
        }
        return null;
    }
}
