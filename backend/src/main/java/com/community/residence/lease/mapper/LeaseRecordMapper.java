package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 租住记录数据访问。 */
@Mapper
public interface LeaseRecordMapper extends BaseMapper<LeaseRecord> {
    @Select("SELECT id FROM lease_record WHERE house_id=#{houseId} AND status IN ('PENDING','TO_SIGN','ACTIVE') FOR UPDATE")
    java.util.List<Long> selectBlockingIdsForUpdate(@Param("houseId") Long houseId);

    /* 协议发出、同意、撤回及管理方状态变更共用租约行锁；使用当前读避免旧快照参与流转。 */
    @Select("SELECT * FROM lease_record WHERE id = #{id} FOR UPDATE")
    LeaseRecord selectByIdForUpdate(@Param("id") Long id);
}
