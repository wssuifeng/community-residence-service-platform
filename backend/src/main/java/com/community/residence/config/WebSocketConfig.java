package com.community.residence.config;

import com.community.residence.config.ConversationSubscriptionInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSocket/STOMP 配置（C11 通知实时推送 + C6 反馈会话 + C12 看房会话 +
 * R63 多方会话，架构设计.md §3.4）。统一端点 /ws（SockJS 兼容）：通知走
 * /user/queue/notifications 用户专属队列，反馈会话走 /topic/feedback/{feedbackId}
 * 主题（订阅鉴权见 FeedbackSubscriptionInterceptor），看房会话走
 * /topic/appointment/{appointmentId} 主题（订阅鉴权见
 * AppointmentSubscriptionInterceptor，R59），多方会话走
 * /topic/conversation/{conversationId} 主题（订阅鉴权见
 * ConversationSubscriptionInterceptor，R63）；认证在 CONNECT 帧由
 * WebSocketAuthInterceptor 完成。
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final FeedbackSubscriptionInterceptor feedbackSubscriptionInterceptor;
    private final AppointmentSubscriptionInterceptor appointmentSubscriptionInterceptor;
    private final ConversationSubscriptionInterceptor conversationSubscriptionInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        /* 简单代理需注册 /queue：用户订阅 /user/queue/* 会被翻译为会话级
           /queue/*-user{sessionId} 目的地，代理不认该前缀则订阅被静默丢弃 */
        registry.enableSimpleBroker("/queue", "/topic");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor, feedbackSubscriptionInterceptor,
                appointmentSubscriptionInterceptor, conversationSubscriptionInterceptor);
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> converters) {
        /* STOMP 载荷序列化统一走带 JavaTimeModule 的 ObjectMapper：默认 converter 将
           LocalDateTime 序列化为数组（如 [2026,9,20,7,53,36]），前端按 ISO 字符串解析
           会触发渲染异常（R59 会话消息不实时显示的根因）；返回 true 表示替换默认链 */
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        converters.add(converter);
        return true;
    }
}
