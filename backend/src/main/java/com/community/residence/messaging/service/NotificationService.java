package com.community.residence.messaging.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.messaging.entity.Notification;
import com.community.residence.messaging.entity.NotificationChannelLog;
import com.community.residence.messaging.mapper.NotificationChannelLogMapper;
import com.community.residence.messaging.mapper.NotificationMapper;
import com.community.residence.messaging.vo.NotificationVO;
import com.community.residence.resident.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知业务逻辑：站内通知统一生成（P2 升级 WebSocket 实时推送）。
 * 生成时若接收人 WebSocket 在线则即时推送，离线由前端登录后 pull 补拉；
 * seq 由 Redis INCR 生成，Redis 不可用时以毫秒时间戳兜底（保持递增性）。
 * 通知为用户私有数据，全部查询按当前登录用户约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String SEQ_KEY = "notification:seq";

    /** 渠道分级配置键（R51：按通知等级勾选模拟渠道，JSON 承载于 sys_config） */
    public static final String KEY_CHANNEL_LEVELS = "notify.channel.levels";

    /** 合法站外模拟渠道（站内 WEBSOCKET 必达，不在配置范围） */
    private static final List<String> VALID_CHANNELS = List.of("EMAIL", "SMS");

    private final NotificationMapper notificationMapper;
    private final NotificationChannelLogMapper channelLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;
    private final SysConfigService sysConfigService;

    /** 生成通知（业务模块调用）：seq 全局递增；接收人在线时实时推送 */
    @Transactional(rollbackFor = Exception.class)
    public void create(Long userId, Long communityId, String title, String content,
                       String type, String sourceType, Long sourceId) {
        Notification notification = new Notification();
        notification.setSeq(nextSeq());
        notification.setUserId(userId);
        notification.setCommunityId(communityId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setType(type);
        notification.setSourceType(sourceType);
        notification.setSourceId(sourceId);
        notification.setChannels("WEBSOCKET");
        notification.setIsRead(0);
        notificationMapper.insert(notification);

        /* 渠道留痕（R51）：站内必达记 WEBSOCKET；按等级配置勾选的模拟渠道各记一条 */
        recordChannels(notification);

        /* 推送失败不影响通知落库（事务内异常回滚会连带丢通知），由轮询兜底 */
        if (webSocketSessionService.isOnline(userId)) {
            try {
                messagingTemplate.convertAndSendToUser(userId.toString(),
                        "/queue/notifications", NotificationVO.from(notification));
            } catch (Exception e) {
                log.warn("WebSocket 推送失败，由轮询兜底：userId={}", userId, e);
            }
        }
    }

    public PageVO<NotificationVO> page(long page, long size, Boolean isRead) {
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .eq(isRead != null, Notification::getIsRead, Boolean.TRUE.equals(isRead) ? 1 : 0)
                        .orderByDesc(Notification::getSeq));
        return PageVO.of(result.convert(NotificationVO::from));
    }

    /** 未读通知列表（P1 轮询端点） */
    public List<NotificationVO> unread() {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .eq(Notification::getIsRead, 0)
                        .orderByDesc(Notification::getSeq))
                .stream().map(NotificationVO::from).toList();
    }

    /** 增量拉取：seq 大于游标的通知（断线补拉；P2 WebSocket 兜底同口径） */
    public List<NotificationVO> pull(Long sinceSeq) {
        long cursor = sinceSeq != null ? sinceSeq : 0;
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .gt(Notification::getSeq, cursor)
                        .orderByAsc(Notification::getSeq))
                .stream().map(NotificationVO::from).toList();
    }

    /* 标记已读：仅本人通知 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        Notification notification = notificationMapper.selectById(id);
        if (notification == null) {
            throw new ResourceNotFoundException("通知不存在");
        }
        if (!notification.getUserId().equals(SecurityUtils.getUserId())) {
            throw new com.community.residence.common.exception.ForbiddenException("无权操作他人通知");
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .set(Notification::getIsRead, 1));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead() {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getUserId, SecurityUtils.getUserId())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
    }

    /** seq 生成：Redis INCR；不可用时毫秒时间戳兜底（仍全局递增） */
    private Long nextSeq() {
        try {
            Long seq = redisTemplate.opsForValue().increment(SEQ_KEY);
            return seq != null ? seq : System.currentTimeMillis();
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，通知 seq 以时间戳兜底");
            return System.currentTimeMillis();
        }
    }

    /* 渠道留痕（R51）：站内必达；模拟渠道按 sys_config notify.channel.levels
       中该通知等级勾选的渠道写 SUCCESS 记录，未勾选不写（否定场景） */
    private void recordChannels(Notification notification) {
        try {
            List<String> channels = new ArrayList<>(List.of("WEBSOCKET"));
            channels.addAll(configuredChannelsFor(notification.getType()));
            for (String channel : channels) {
                NotificationChannelLog entry = new NotificationChannelLog();
                entry.setNotificationId(notification.getId());
                entry.setChannel(channel);
                entry.setStatus("SUCCESS");
                entry.setSentTime(LocalDateTime.now());
                channelLogMapper.insert(entry);
            }
        } catch (Exception e) {
            /* 留痕失败不阻断通知生成（留痕降级为 WARN，通知本体已落库） */
            log.warn("渠道留痕写入失败：notificationId={}", notification.getId(), e);
        }
    }

    /** 读取某通知等级勾选的模拟渠道（配置缺失/非法回退为空=仅站内） */
    private List<String> configuredChannelsFor(String level) {
        Map<String, List<String>> levels = readChannelLevels();
        List<String> channels = levels.get(level);
        if (channels == null) {
            return List.of();
        }
        return channels.stream().filter(VALID_CHANNELS::contains).distinct().toList();
    }

    /** 渠道分级配置读取（sys_config notify.channel.levels JSON；缺省空映射） */
    public Map<String, List<String>> readChannelLevels() {
        String json = sysConfigService.getValue(KEY_CHANNEL_LEVELS);
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
                    });
        } catch (Exception e) {
            log.warn("渠道分级配置解析失败，回退仅站内：{}", json);
            return Map.of();
        }
    }

    /** 渠道分级配置更新（仅超管，Controller 层声明；校验渠道合法后写回 sys_config） */
    public void updateChannelLevels(Map<String, List<String>> levels) {
        for (Map.Entry<String, List<String>> entry : levels.entrySet()) {
            for (String channel : entry.getValue()) {
                if (!VALID_CHANNELS.contains(channel)) {
                    throw new com.community.residence.common.exception.BusinessException(
                            com.community.residence.common.constant.ErrorCode.INVALID_PARAM,
                            "非法渠道：" + channel + "（仅支持 " + VALID_CHANNELS + "）");
                }
            }
        }
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(levels);
            sysConfigService.upsert(KEY_CHANNEL_LEVELS, json, "通知渠道分级配置（R51：等级→勾选模拟渠道）");
        } catch (com.community.residence.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new com.community.residence.common.exception.BusinessException(
                    com.community.residence.common.constant.ErrorCode.OPERATION_FAILED, "渠道配置序列化失败");
        }
    }
}
