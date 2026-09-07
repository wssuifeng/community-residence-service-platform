package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.SysOperationLog;
import org.apache.ibatis.annotations.Mapper;

/** 操作日志数据访问（查询侧；写入由 AOP 切面调用 insert） */
@Mapper
public interface SysOperationLogMapper extends BaseMapper<SysOperationLog> {
}
