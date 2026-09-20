package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseChangeLog;
import org.apache.ibatis.annotations.Mapper;

/* 租约属性变更历史：按租约查询变更流水（写入由 LeaseChangeLogService 统一负责） */
@Mapper
public interface LeaseChangeLogMapper extends BaseMapper<LeaseChangeLog> {
}
