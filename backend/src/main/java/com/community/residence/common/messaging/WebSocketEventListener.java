package com.community.residence.common.messaging;

import com.community.residence.common.security.context.IdentityPrincipal;
import com.community.residence.common.messaging.service.WebSocketSessionService;
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
 * （ws:session:{主体名}，主体名带身份空间前缀 R:/U:，DEF-067），供通知推送前判断在线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final WebSocketSessionService webSocketSessionService;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        IdentityPrincipal principal = resolvePrincipal(event.getMessage().getHeaders());
        if (principal != null) {
            webSocketSessionService.register(principal.getOwnerType(), principal.getUserId());
            log.debug("WebSocket 上线：subject={}", principal.getName());
        }
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        IdentityPrincipal principal = resolvePrincipal(event.getMessage().getHeaders());
        if (principal != null) {
            webSocketSessionService.deregister(principal.getOwnerType(), principal.getUserId());
            log.debug("WebSocket 下线：subject={}", principal.getName());
        }
    }

    /** Principal 由 WebSocketAuthInterceptor 在 CONNECT 帧注入（name = R:{id}/U:{id}） */
    private IdentityPrincipal resolvePrincipal(org.springframework.messaging.MessageHeaders headers) {
        Object raw = headers.get(SimpMessageHeaderAccessor.USER_HEADER);
        if (raw instanceof UsernamePasswordAuthenticationToken auth
                && auth.getPrincipal() instanceof IdentityPrincipal principal) {
            return principal;
        }
        return null;
    }
}
