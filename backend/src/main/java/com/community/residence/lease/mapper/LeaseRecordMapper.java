package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseRecord;
import org.apache.ibatis.annotations.Mapper;

/** 租住记录数据访问 */
@Mapper
public interface LeaseRecordMapper extends BaseMapper<LeaseRecord> {
}
