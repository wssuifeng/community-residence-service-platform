package com.community.residence.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
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
 * 看房会话订阅鉴权拦截器（R59，复制 R30 FeedbackSubscriptionInterceptor 模式）：
 * /topic/appointment/{appointmentId} 仅会话相关方可订阅——预约居民、带看人
 * （assigned_staff_id，STAFF/ADMIN 皆可）、该房源所在社区的 ADMIN（sys_admin_community
 * 实时查询，WS 连接不缓存绑定）、SUPER_ADMIN 放行；非参与者订阅即拒。
 * 口径与 HTTP 读路径（ViewingAppointmentService#checkMessageReadAccess）一致。
 * Principal 由 WebSocketAuthInterceptor 在 CONNECT 帧注入。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppointmentSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern APPOINTMENT_TOPIC = Pattern.compile("^/topic/appointment/(\\d+)$");

    private final ViewingAppointmentMapper appointmentMapper;
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
        Matcher matcher = APPOINTMENT_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }
        Long appointmentId = Long.valueOf(matcher.group(1));
        if (!(accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth)) {
            throw new MessageDeliveryException("Forbidden: 订阅看房会话需先完成认证");
        }
        if (!(auth.getPrincipal() instanceof Long userId)) {
            throw new MessageDeliveryException("Forbidden: 订阅主体无效");
        }
        String role = resolveRole(auth);
        if (!hasSubscriptionAccess(appointmentId, userId, role)) {
            log.warn("看房会话订阅被拒：appointmentId={}, userId={}, role={}", appointmentId, userId, role);
            throw new MessageDeliveryException("Forbidden: 非看房会话参与者，拒绝订阅");
        }
        return message;
    }

    private String resolveRole(UsernamePasswordAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .findFirst().orElse("");
    }

    /* 订阅权限与 HTTP 读路径同口径（checkMessageReadAccess）：预约居民、带看人、
       社区 ADMIN（按绑定实时查）、SUPER_ADMIN 放行 */
    private boolean hasSubscriptionAccess(Long appointmentId, Long userId, String role) {
        ViewingAppointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            return false;
        }
        if (RoleConstants.SUPER_ADMIN.equals(role)) {
            return true;
        }
        if (RoleConstants.RESIDENT.equals(role)) {
            return appointment.getUserId() != null && appointment.getUserId().equals(userId);
        }
        if (appointment.getAssignedStaffId() != null && appointment.getAssignedStaffId().equals(userId)) {
            return true;
        }
        if (RoleConstants.ADMIN.equals(role)) {
            Long bindings = sysAdminCommunityMapper.selectCount(new LambdaQueryWrapper<SysAdminCommunity>()
                    .eq(SysAdminCommunity::getAdminId, userId)
                    .eq(SysAdminCommunity::getCommunityId, appointment.getCommunityId()));
            return bindings != null && bindings > 0;
        }
        return false;
    }
}
