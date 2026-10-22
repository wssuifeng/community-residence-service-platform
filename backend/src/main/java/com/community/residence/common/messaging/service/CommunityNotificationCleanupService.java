package com.community.residence.common.messaging.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.messaging.entity.Notification;
import com.community.residence.common.messaging.entity.NotificationChannelLog;
import com.community.residence.common.messaging.mapper.NotificationMapper;
import com.community.residence.common.messaging.mapper.NotificationChannelLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 小区删除时的messaging域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityNotificationCleanupService {
    private final NotificationMapper notificationMapper;
    private final NotificationChannelLogMapper notificationChannelLogMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeNotifications(Long id) {
        LambdaQueryWrapper<Notification> notificationScope = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getCommunityId, id);
        List<Long> notificationIds = notificationMapper.selectList(notificationScope).stream()
                .map(Notification::getId)
                .toList();
        /* notification_channel_log 以 notification_id 外键依赖通知，必须先删渠道留痕 */
        if (!notificationIds.isEmpty()) {
            notificationChannelLogMapper.delete(new LambdaQueryWrapper<NotificationChannelLog>()
                    .in(NotificationChannelLog::getNotificationId, notificationIds));
        }
        notificationMapper.delete(notificationScope);

    }
}
