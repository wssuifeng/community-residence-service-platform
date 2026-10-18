package com.community.residence.common.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.workorder.entity.ServiceCategory;
import org.apache.ibatis.annotations.Mapper;

/** 服务类别数据访问 */
@Mapper
public interface ServiceCategoryMapper extends BaseMapper<ServiceCategory> {
}
