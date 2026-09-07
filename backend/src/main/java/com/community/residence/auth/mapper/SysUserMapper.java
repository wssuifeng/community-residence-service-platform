package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 系统用户表访问 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /* 按用户名查询（登录用，唯一键 uk_username 保证至多一条） */
    @Select("SELECT id, username, password_hash, real_name, phone, email, avatar_url, role, status, "
            + "created_at, updated_at FROM sys_user WHERE username = #{username}")
    SysUser selectByUsername(@Param("username") String username);
}
