package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.Community;
import org.apache.ibatis.annotations.Mapper;

/** 社区数据访问（数据级权限：community 本表按 id 过滤，由拦截器处理） */
@Mapper
public interface CommunityMapper extends BaseMapper<Community> {
}
