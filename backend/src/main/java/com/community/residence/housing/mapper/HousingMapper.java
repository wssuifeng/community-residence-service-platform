package com.community.residence.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.housing.entity.Housing;
import org.apache.ibatis.annotations.Mapper;

/** 房源数据访问 */
@Mapper
public interface HousingMapper extends BaseMapper<Housing> {
}
