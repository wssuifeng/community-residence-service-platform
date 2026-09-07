package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.AuthTokenBlacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/** JWT 令牌黑名单表访问 */
@Mapper
public interface AuthTokenBlacklistMapper extends BaseMapper<AuthTokenBlacklist> {

    /* 按 jti 查未过期黑名单记录（Redis 降级时的权威校验来源） */
    @Select("SELECT id, jti, user_id, expire_time, reason, blacklist_time "
            + "FROM auth_token_blacklist WHERE jti = #{jti} AND expire_time > NOW()")
    AuthTokenBlacklist selectByJtiNotExpired(@Param("jti") String jti);

    /* 用户级吊销的最新时间（冻结/改密/权限变更后旧令牌全部失效；Redis 降级权威来源） */
    @Select("SELECT MAX(blacklist_time) FROM auth_token_blacklist "
            + "WHERE user_id = #{userId} AND reason = 'PERMISSION_CHANGE' AND expire_time > NOW()")
    LocalDateTime selectLatestRevocationTime(@Param("userId") Long userId);
}
