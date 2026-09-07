package com.community.residence.messaging.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.messaging.entity.Notification;
import com.community.residence.messaging.mapper.NotificationMapper;
import com.community.residence.messaging.vo.NotificationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 通知业务逻辑：P1 HTTP 轮询（列表/未读/seq 增量拉取），P2 升级 WebSocket 推送。
 * seq 由 Redis INCR 生成，Redis 不可用时以毫秒时间戳兜底（保持递增性）。
 * 通知为用户私有数据，全部查询按当前登录用户约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String SEQ_KEY = "notification:seq";

    private final NotificationMapper notificationMapper;
    private final StringRedisTemplate redisTemplate;

    /** 生成通知（业务模块调用）：seq 全局递增 */
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
    }

    public PageVO<NotificationVO> page(long page, long size, Boolean isRead) {
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .eq(isRead != null, Notification::getIsRead, isRead ? 1 : 0)
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
}
