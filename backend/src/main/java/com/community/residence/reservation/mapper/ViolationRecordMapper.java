package com.community.residence.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.reservation.entity.ViolationRecord;
import org.apache.ibatis.annotations.Mapper;

/** 违约处置记录数据访问 */
@Mapper
public interface ViolationRecordMapper extends BaseMapper<ViolationRecord> {
}
