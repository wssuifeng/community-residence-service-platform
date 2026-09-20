package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.StaffServiceCategory;
import org.apache.ibatis.annotations.Mapper;

/* 服务人员擅长类别数据访问（无 community_id 列，在拦截器 SKIP_TABLES 中，
   可见性由业务层经 staff_id 归属约束） */
@Mapper
public interface StaffServiceCategoryMapper extends BaseMapper<StaffServiceCategory> {
}
