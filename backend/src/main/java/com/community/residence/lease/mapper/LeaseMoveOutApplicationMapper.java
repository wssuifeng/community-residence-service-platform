package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseMoveOutApplication;
import org.apache.ibatis.annotations.Mapper;

/* 居民自助退租申请（R70）：按租约查询与待审唯一性校验由 LambdaQueryWrapper 表达，
   社区级联删除按 community_id 批量清理 */
@Mapper
public interface LeaseMoveOutApplicationMapper extends BaseMapper<LeaseMoveOutApplication> {
}
