package com.community.residence.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求（居民端与管理端共用结构） */
@Data
@Schema(description = "登录请求")
public class LoginDTO {

    @Schema(description = "用户名", example = "admin")
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Schema(description = "密码", example = "Admin123456")
    @NotBlank(message = "密码不能为空")
    private String password;
}
