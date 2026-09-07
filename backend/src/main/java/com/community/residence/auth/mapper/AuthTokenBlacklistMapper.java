package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.AuthTokenBlacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** JWT 令牌黑名单表访问 */
@Mapper
public interface AuthTokenBlacklistMapper extends BaseMapper<AuthTokenBlacklist> {

    /* 按 jti 查未过期黑名单记录（Redis 降级时的权威校验来源） */
    @Select("SELECT id, jti, user_id, expire_time, reason, blacklist_time "
            + "FROM auth_token_blacklist WHERE jti = #{jti} AND expire_time > NOW()")
    AuthTokenBlacklist selectByJtiNotExpired(@Param("jti") String jti);
}
