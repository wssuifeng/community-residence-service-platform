package com.community.residence.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.conversation.entity.ConversationParticipant;
import com.community.residence.conversation.mapper.ConversationParticipantMapper;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话订阅鉴权拦截器（R63，复制 R59 AppointmentSubscriptionInterceptor 模式）：
 * /topic/conversation/{conversationId} 仅会话参与者可订阅——以
 * conversation_participant 表实时校验（WS 连接不缓存参与者快照，入群即时生效）；
 * 非参与者订阅即拒。口径与 HTTP 读写路径（ConversationService#requireParticipant）
 * 一致；SUPER_ADMIN 不放行（其非业务参与者，越权访问 100% 拒绝口径）。
 * Principal 由 WebSocketAuthInterceptor 在 CONNECT 帧注入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CONVERSATION_TOPIC = Pattern.compile("^/topic/conversation/(\\d+)$");

    private final ConversationParticipantMapper participantMapper;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }
        Matcher matcher = CONVERSATION_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }
        Long conversationId = Long.valueOf(matcher.group(1));
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)) {
            throw new MessageDeliveryException("Forbidden: 订阅会话需先完成认证");
        }
        if (!(auth.getPrincipal() instanceof Long userId)) {
            throw new MessageDeliveryException("Forbidden: 订阅主体无效");
        }
        if (!isParticipant(conversationId, userId)) {
            log.warn("会话订阅被拒：conversationId={}, userId={}, role={}",
                    conversationId, userId, resolveRole(auth));
            throw new MessageDeliveryException("Forbidden: 非会话参与者，拒绝订阅");
        }
        return message;
    }

    private String resolveRole(UsernamePasswordAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .findFirst().orElse("");
    }

    /* 参与者实时校验：与 HTTP 读写路径同口径（conversation_participant 存在性） */
    private boolean isParticipant(Long conversationId, Long userId) {
        return participantMapper.exists(new LambdaQueryWrapper<ConversationParticipant>()
                .eq(ConversationParticipant::getConversationId, conversationId)
                .eq(ConversationParticipant::getUserId, userId));
    }
}
