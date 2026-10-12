package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.Community;
import org.apache.ibatis.annotations.Mapper;

/** 社区数据访问（数据级权限：community 本表按 id 过滤，由拦截器处理） */
@Mapper
public interface CommunityMapper extends BaseMapper<Community> {
    /* 改角色前锁定读引用，避免把正在使用的责任人改成非管理员。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM community WHERE responsible_admin_id=#{adminId} LIMIT 1 FOR UPDATE")
    Long selectResponsibleReferenceForUpdate(@org.apache.ibatis.annotations.Param("adminId") Long adminId);
}
