package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.ResourceTimeslot;
import org.apache.ibatis.annotations.Mapper;

/** 资源可预约时段数据访问 */
@Mapper
public interface ResourceTimeslotMapper extends BaseMapper<ResourceTimeslot> {
}
