package com.community.residence.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.housing.entity.HousingTimeslot;
import org.apache.ibatis.annotations.Mapper;

/** 房源可预约时段数据访问 */
@Mapper
public interface HousingTimeslotMapper extends BaseMapper<HousingTimeslot> {
}
