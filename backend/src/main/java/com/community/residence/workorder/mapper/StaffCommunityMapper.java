package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.StaffCommunity;
import org.apache.ibatis.annotations.Mapper;

/* 服务人员常驻社区绑定数据访问（有 community_id 列，ADMIN 数据级权限由拦截器注入） */
@Mapper
public interface StaffCommunityMapper extends BaseMapper<StaffCommunity> {
}
