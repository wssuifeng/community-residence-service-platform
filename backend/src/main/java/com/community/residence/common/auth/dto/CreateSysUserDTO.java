package com.community.residence.common.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建系统用户请求（管理员/服务人员账号，仅超管操作） */
@Data
@Schema(description = "创建系统用户请求")
public class CreateSysUserDTO {

    @Schema(description = "用户名")
    @NotBlank(message = "用户名不能为空")
    @Size(min = 1, max = 50, message = "用户名长度1～50字符")
    @Pattern(regexp = "^[\\p{L}\\p{N}_]+$", message = "用户名支持中文、字母、数字、下划线，不支持空格或特殊符号")
    private String username;

    @Schema(description = "初始密码")
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 20, message = "密码长度 8~20 字符")
    private String password;

    @Schema(description = "真实姓名")
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "真实姓名最多 50 字符")
    private String realName;

    @Schema(description = "手机号")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "邮箱")
    @Email(message = "邮箱格式不正确")
    private String email;

    @Schema(description = "角色：SUPER_ADMIN-超级管理员, ADMIN-小区管理员, STAFF-服务人员")
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "^(SUPER_ADMIN|ADMIN|STAFF)$", message = "角色不合法")
    private String role;
}
