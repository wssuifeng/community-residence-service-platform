package com.community.residence.resident.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.resident.entity.ResidenceApplication;
import org.apache.ibatis.annotations.Mapper;

/** 入住申请数据访问 */
@Mapper
public interface ResidenceApplicationMapper extends BaseMapper<ResidenceApplication> {
}
