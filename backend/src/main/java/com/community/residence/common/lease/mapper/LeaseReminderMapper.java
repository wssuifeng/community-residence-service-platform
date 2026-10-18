package com.community.residence.common.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.lease.entity.LeaseReminder;
import org.apache.ibatis.annotations.Mapper;

/** 租期到期提醒去重表访问 */
@Mapper
public interface LeaseReminderMapper extends BaseMapper<LeaseReminder> {
}
