package com.community.residence.common.messaging.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.messaging.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 通知数据访问 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
    /* 进程首次发通知时恢复已持久化的序号下限，防止 Redis 重建后游标回退。 */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM notification")
    long selectMaxSeq();
}
