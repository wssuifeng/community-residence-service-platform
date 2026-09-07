package com.community.residence.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

/** 登录成功响应（token + 用户摘要，字段对齐接口设计.md 9.2.1.2 / 9.10.1.1） */
@Data
@Builder
@Schema(description = "登录结果")
public class LoginVO {

    @Schema(description = "JWT 访问令牌")
    private String token;

    @Schema(description = "令牌有效期（秒）", example = "7200")
    private long expiresIn;

    @Schema(description = "用户摘要")
    private UserSummary user;

    @Data
    @Builder
    @Schema(description = "用户摘要")
    public static class UserSummary {

        @Schema(description = "用户ID")
        private Long id;

        @Schema(description = "用户名")
        private String username;

        @Schema(description = "真实姓名")
        private String realName;

        @Schema(description = "角色：RESIDENT/STAFF/ADMIN/SUPER_ADMIN")
        private String role;

        @Schema(description = "绑定社区 ID 列表（仅 ADMIN 非空）")
        private Set<Long> boundCommunities;
    }
}
