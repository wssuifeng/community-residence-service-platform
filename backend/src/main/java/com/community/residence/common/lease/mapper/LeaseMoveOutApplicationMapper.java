package com.community.residence.common.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/* 居民自助退租申请（R70）：按租约查询与待审唯一性校验由 LambdaQueryWrapper 表达，
   小区级联删除按 community_id 批量清理 */
@Mapper
public interface LeaseMoveOutApplicationMapper extends BaseMapper<LeaseMoveOutApplication> {
    /* 审核持有房屋/租约锁后重读申请当前终态，避免读取等待锁前的事务快照。 */
    @Select("SELECT id, lease_id, resident_id, community_id, house_id, move_out_date, reason, "
            + "status, reviewer_id, review_time, reject_reason, created_at, updated_at "
            + "FROM lease_move_out_application WHERE id = #{id} FOR UPDATE")
    LeaseMoveOutApplication selectByIdForUpdate(Long id);
}
