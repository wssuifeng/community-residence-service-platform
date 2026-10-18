package com.community.residence.common.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求（居民端与管理端共用结构） */
@Data
@Schema(description = "登录请求")
public class LoginDTO {

    @Schema(description = "用户名或注册手机号（兼容字段username）", example = "admin")
    @NotBlank(message = "用户名或手机号不能为空")
    private String username;

    @Schema(description = "密码", example = "Admin123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
