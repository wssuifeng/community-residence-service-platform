package com.community.residence.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.messaging.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/** 通知数据访问 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
