package com.community.residence.schedule.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.schedule.entity.SysTaskLog;
import org.apache.ibatis.annotations.Mapper;

/** 定时任务执行日志表访问 */
@Mapper
public interface SysTaskLogMapper extends BaseMapper<SysTaskLog> {
}
