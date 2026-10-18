package com.community.residence.common.resident.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.resident.entity.Resident;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 居民账号表访问 */
@Mapper
public interface ResidentMapper extends BaseMapper<Resident> {

    /* 同一居民的看房违约计数与冻结串行化；不改变账号的社区归属模型。 */
    @Select("SELECT * FROM resident WHERE id = #{id} FOR UPDATE")
    Resident selectByIdForUpdate(@Param("id") Long id);


    /* 按用户名查询（登录用，唯一键 uk_username 保证至多一条） */
    @Select("SELECT id, username, password_hash, real_name, phone, id_card, email, avatar_url, "
            + "status, created_at, updated_at FROM resident WHERE username = #{username}")
    Resident selectByUsername(@Param("username") String username);
    /* 同身份空间用户名/手机号均可登录；最多读两条，歧义由认证层拒绝，不猜优先级。 */
    @Select("SELECT id, username, password_hash, real_name, phone, id_card, email, avatar_url, status, created_at, updated_at FROM resident WHERE username = #{identifier} OR phone = #{identifier} LIMIT 2")
    java.util.List<Resident> selectLoginCandidates(@Param("identifier") String identifier);
}
