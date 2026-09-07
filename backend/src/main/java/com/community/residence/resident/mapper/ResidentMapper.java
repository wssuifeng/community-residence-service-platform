package com.community.residence.resident.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.resident.entity.Resident;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 居民账号表访问 */
@Mapper
public interface ResidentMapper extends BaseMapper<Resident> {

    /* 按用户名查询（登录用，唯一键 uk_username 保证至多一条） */
    @Select("SELECT id, username, password_hash, real_name, phone, id_card, email, avatar_url, "
            + "status, created_at, updated_at FROM resident WHERE username = #{username}")
    Resident selectByUsername(@Param("username") String username);
}
