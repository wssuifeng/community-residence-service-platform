package com.community.residence.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.messaging.entity.NotificationChannelLog;
import org.apache.ibatis.annotations.Mapper;

/* 通知渠道发送记录数据访问（R51 渠道留痕；模拟渠道写 SUCCESS 记录） */
@Mapper
public interface NotificationChannelLogMapper extends BaseMapper<NotificationChannelLog> {
}
