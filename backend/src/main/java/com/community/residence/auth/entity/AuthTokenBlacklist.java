package com.community.residence.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** JWT 令牌黑名单（auth_token_blacklist：DB 为权威来源，Redis 为性能缓存，架构设计.md §3.1） */
@Data
@TableName("auth_token_blacklist")
@Schema(description = "JWT令牌黑名单")
public class AuthTokenBlacklist {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "JWT 唯一标识")
    private String jti;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "令牌过期时间")
    private LocalDateTime expireTime;

    @Schema(description = "拉黑原因：LOGOUT-主动登出, PERMISSION_CHANGE-权限变更")
    private String reason;

    @Schema(description = "拉黑时间")
    private LocalDateTime blacklistTime;
}
