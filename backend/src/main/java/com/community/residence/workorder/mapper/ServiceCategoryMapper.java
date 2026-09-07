package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.ServiceCategory;
import org.apache.ibatis.annotations.Mapper;

/** 服务类别数据访问 */
@Mapper
public interface ServiceCategoryMapper extends BaseMapper<ServiceCategory> {
}
