package com.community.residence.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.feedback.entity.Feedback;
import com.community.residence.feedback.mapper.FeedbackMapper;
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

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 反馈会话订阅鉴权拦截器（R30，接口设计 §9.6.3.3）：/topic/feedback/{feedbackId}
 * 仅会话参与者可订阅——RESIDENT 限反馈提交人，ADMIN 限绑定社区覆盖该反馈所属
 * 社区（sys_admin_community 实时查询，WS 连接不缓存绑定），SUPER_ADMIN 放行；
 * STAFF/GUEST 非会话角色（§6.1 权限仅 RESIDENT/ADMIN），非参与者订阅即拒。
 * Principal 由 WebSocketAuthInterceptor 在 CONNECT 帧注入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern FEEDBACK_TOPIC = Pattern.compile("^/topic/feedback/(\\d+)$");

    private final FeedbackMapper feedbackMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;

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
        Matcher matcher = FEEDBACK_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }
        Long feedbackId = Long.valueOf(matcher.group(1));
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)) {
            throw new MessageDeliveryException("Forbidden: 订阅反馈会话需先完成认证");
        }
        if (!(auth.getPrincipal() instanceof Long userId)) {
            throw new MessageDeliveryException("Forbidden: 订阅主体无效");
        }
        String role = resolveRole(auth);
        if (!hasSubscriptionAccess(feedbackId, userId, role)) {
            log.warn("反馈会话订阅被拒：feedbackId={}, userId={}, role={}", feedbackId, userId, role);
            throw new MessageDeliveryException("Forbidden: 非反馈会话参与者，拒绝订阅");
        }
        return message;
    }

    private String resolveRole(UsernamePasswordAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .findFirst().orElse("");
    }

    /* 订阅权限与 HTTP 读路径同口径（checkReadAccess + checkCommunityAccess） */
    private boolean hasSubscriptionAccess(Long feedbackId, Long userId, String role) {
        Feedback feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null) {
            return false;
        }
        if (RoleConstants.SUPER_ADMIN.equals(role)) {
            return true;
        }
        if (RoleConstants.RESIDENT.equals(role)) {
            return feedback.getResidentId().equals(userId);
        }
        if (RoleConstants.ADMIN.equals(role)) {
            Long bindings = sysAdminCommunityMapper.selectCount(new LambdaQueryWrapper<SysAdminCommunity>()
                    .eq(SysAdminCommunity::getAdminId, userId)
                    .eq(SysAdminCommunity::getCommunityId, feedback.getCommunityId()));
            return bindings != null && bindings > 0;
        }
        return false;
    }
}
