package com.community.residence.common.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.housing.entity.HousingTimeslot;
import org.apache.ibatis.annotations.Mapper;

/** 房源可预约时段数据访问 */
@Mapper
public interface HousingTimeslotMapper extends BaseMapper<HousingTimeslot> {
}
