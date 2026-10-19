package com.community.residence.common.messaging.service;

import com.community.residence.common.config.RedisFailures;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.messaging.constant.NotificationOwnerType;
import com.community.residence.common.security.context.IdentityPrincipal;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.messaging.entity.Notification;
import com.community.residence.common.messaging.entity.NotificationChannelLog;
import com.community.residence.common.messaging.mapper.NotificationChannelLogMapper;
import com.community.residence.common.messaging.mapper.NotificationMapper;
import com.community.residence.common.messaging.vo.NotificationVO;
import com.community.residence.common.auth.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 通知业务逻辑：站内通知统一生成（P2 升级 WebSocket 实时推送）。
 * 生成时若接收人 WebSocket 在线则即时推送，离线由前端登录后 pull 补拉；
 * seq 由 Redis 原子递增生成；故障时沿用本进程序号下限，恢复及重启后不回退。
 * <p>
 * 收件人身份为 **(ownerType, userId) 二元组**（DEF-067）：resident 表与 sys_user
 * 表是两套独立身份空间且主键可撞号（resident 1 与 sys_user 1），单靠 user_id
 * 会双向串收，故生成、查询、已读、增量拉取、WebSocket 目的地全部按该二元组收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery;

    private static final String SEQ_KEY = "notification:seq";
    private static final DefaultRedisScript<Long> NEXT_SEQ = new DefaultRedisScript<>("""
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local floor = tonumber(ARGV[1])
            if current < floor then redis.call('SET', KEYS[1], ARGV[1]) end
            return redis.call('INCR', KEYS[1])
            """, Long.class);
    private Long lastSeq;

    /** 渠道分级配置键（R51：按通知等级勾选模拟渠道，JSON 承载于 sys_config） */
    public static final String KEY_CHANNEL_LEVELS = "notify.channel.levels";

    /** 合法站外模拟渠道（站内 WEBSOCKET 必达，不在配置范围） */
    private static final List<String> VALID_CHANNELS = List.of("EMAIL", "SMS");

    /** 合法通知等级键（DEF-042 白名单）：与 createForUser/createForResident 全部调用点的
     *  type 实参集一致（WORK_ORDER/NOTICE/FEEDBACK/RESERVATION/RESIDENCE/LEASE/SYSTEM——
     *  RESOURCE_RESERVATION 等为 sourceType 来源标识，不参与等级匹配），未知键 400 防配置漂移 */
    private static final List<String> VALID_LEVELS = List.of(
            "WORK_ORDER", "NOTICE", "FEEDBACK", "RESERVATION", "RESIDENCE", "LEASE", "SYSTEM");

    private final NotificationMapper notificationMapper;
    private final NotificationChannelLogMapper channelLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final WebSocketSessionService webSocketSessionService;
    private final SysConfigService sysConfigService;

    /**
     * 生成员工/管理员通知（收件人取自 sys_user 身份空间）：
     * boundAdminIds(communityId)、assigneeId/handlerId 等 sys_user.id，以及超管账号。
     * seq 全局递增；接收人在线时实时推送（目的地主体名 U:{sysUserId}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createForUser(Long userId, Long communityId, String title, String content,
                              String type, String sourceType, Long sourceId) {
        create(NotificationOwnerType.USER, userId, communityId, title, content, type, sourceType, sourceId);
    }

    /**
     * 生成居民通知（收件人取自 resident 身份空间）：lease.tenantId、
     * application.residentId、payment.residentId、appointment/reservation.userId、
     * 公告群发 residentId 等 resident.id。
     * seq 全局递增；接收人在线时实时推送（目的地主体名 R:{residentId}）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createForResident(Long userId, Long communityId, String title, String content,
                                  String type, String sourceType, Long sourceId) {
        create(NotificationOwnerType.RESIDENT, userId, communityId, title, content, type, sourceType, sourceId);
    }

    /* 通知生成内核：ownerType 为收件人身份空间，与 userId 一起落库并决定推送目的地。
       仅由上面两个显式入口调用——不提供带默认 USER 的重载，避免新调用点漏标身份。 */
    private void create(String ownerType, Long userId, Long communityId, String title, String content,
                        String type, String sourceType, Long sourceId) {
        Notification notification = new Notification();
        notification.setSeq(nextSeq());
        notification.setOwnerType(ownerType);
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

        /* 渠道留痕（R51）：站内必达记 WEBSOCKET；按等级配置勾选的模拟渠道各记一条。
           留痕表经 notification_id 关联本体，身份空间由 notification.owner_type 承载，
           不需要冗余列（无任何按收件人过滤留痕的查询） */
        recordChannels(notification);

        /* 推送失败不影响通知落库（事务内异常回滚会连带丢通知），由轮询兜底；
           在线判定与目的地一律带身份前缀，居民与员工不共用同一 destination（DEF-067） */
        if (webSocketSessionService.isOnline(ownerType, userId)) {
            try {
                String recipient = IdentityPrincipal.subjectName(ownerType, userId);
                NotificationVO payload = NotificationVO.from(notification);
                afterCommitDelivery.deliver(recipient + "/queue/notifications", () ->
                        messagingTemplate.convertAndSendToUser(recipient, "/queue/notifications", payload));
            } catch (Exception e) {
                log.warn("WebSocket 推送失败，由轮询兜底：ownerType={}, userId={}", ownerType, userId, e);
            }
        }
    }

    public PageVO<NotificationVO> page(long page, long size, Boolean isRead) {
        Page<Notification> result = notificationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getOwnerType, currentOwnerType())
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .eq(isRead != null, Notification::getIsRead, Boolean.TRUE.equals(isRead) ? 1 : 0)
                        .orderByDesc(Notification::getSeq));
        return PageVO.of(result.convert(NotificationVO::from));
    }

    /** 未读通知列表（P1 轮询端点） */
    public List<NotificationVO> unread() {
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getOwnerType, currentOwnerType())
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .eq(Notification::getIsRead, 0)
                        .orderByDesc(Notification::getSeq))
                .stream().map(NotificationVO::from).toList();
    }

    /** 增量拉取：seq 大于游标的通知（断线补拉；P2 WebSocket 兜底同口径） */
    public List<NotificationVO> pull(Long sinceSeq) {
        long cursor = sinceSeq != null ? sinceSeq : 0;
        return notificationMapper.selectList(new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getOwnerType, currentOwnerType())
                        .eq(Notification::getUserId, SecurityUtils.getUserId())
                        .gt(Notification::getSeq, cursor)
                        .orderByAsc(Notification::getSeq))
                .stream().map(NotificationVO::from).toList();
    }

    /* 标记已读：仅本人通知——id 相同但身份空间不同（resident 1 / sys_user 1 撞号）
       同样视为他人通知，拒绝越权置读 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long id) {
        Notification notification = notificationMapper.selectById(id);
        if (notification == null) {
            throw new ResourceNotFoundException("通知不存在");
        }
        if (!Objects.equals(notification.getOwnerType(), currentOwnerType())
                || !notification.getUserId().equals(SecurityUtils.getUserId())) {
            throw new com.community.residence.common.web.exception.ForbiddenException("无权操作他人通知");
        }
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .set(Notification::getIsRead, 1));
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead() {
        notificationMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getOwnerType, currentOwnerType())
                .eq(Notification::getUserId, SecurityUtils.getUserId())
                .eq(Notification::getIsRead, 0)
                .set(Notification::getIsRead, 1));
    }

    /* 当前登录主体所属身份空间：RESIDENT 角色取 resident 身份空间，其余取 sys_user；
       与 JwtAuthenticationFilter 的令牌吊销分支同判据，保证读写两侧口径一致 */
    private String currentOwnerType() {
        UserContext user = SecurityUtils.getUser();
        return NotificationOwnerType.ofRole(user == null ? null : user.getRole());
    }

    /* 单体内串行分配序号：首次从数据库恢复下限；Redis 原子抬升下限并递增。
       故障期间同毫秒也不复用序号，恢复后先传播本地最大值，避免 sinceSeq 漏拉。 */
    private synchronized Long nextSeq() {
        if (lastSeq == null) {
            lastSeq = notificationMapper.selectMaxSeq();
        }
        try {
            Long seq = redisTemplate.execute(NEXT_SEQ, List.of(SEQ_KEY), lastSeq.toString());
            if (seq == null || seq <= lastSeq) {
                throw new IllegalStateException("通知序号未递增");
            }
            lastSeq = seq;
        } catch (DataAccessException e) {
            RedisFailures.rethrowUnlessUnavailable(e);
            log.warn("Redis 不可用，通知 seq 沿本地序号下限递增");
            lastSeq = Math.max(lastSeq + 1, System.currentTimeMillis());
        }
        return lastSeq;
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

    /** 渠道分级配置更新（仅超管，Controller 层声明；校验等级键与渠道合法后写回 sys_config） */
    public void updateChannelLevels(Map<String, List<String>> levels) {
        for (Map.Entry<String, List<String>> entry : levels.entrySet()) {
            if (!VALID_LEVELS.contains(entry.getKey())) {
                throw new com.community.residence.common.web.exception.BusinessException(
                        com.community.residence.common.web.constant.ErrorCode.INVALID_PARAM,
                        "非法通知等级：" + entry.getKey() + "（仅支持 " + VALID_LEVELS + "）");
            }
            for (String channel : entry.getValue()) {
                if (!VALID_CHANNELS.contains(channel)) {
                    throw new com.community.residence.common.web.exception.BusinessException(
                            com.community.residence.common.web.constant.ErrorCode.INVALID_PARAM,
                            "非法渠道：" + channel + "（仅支持 " + VALID_CHANNELS + "）");
                }
            }
        }
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(levels);
            sysConfigService.upsert(KEY_CHANNEL_LEVELS, json, "通知渠道分级配置（R51：等级→勾选模拟渠道）");
        } catch (com.community.residence.common.web.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new com.community.residence.common.web.exception.BusinessException(
                    com.community.residence.common.web.constant.ErrorCode.OPERATION_FAILED, "渠道配置序列化失败");
        }
    }
}
