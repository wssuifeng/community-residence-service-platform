package com.community.residence.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 更新系统用户请求（用户名与密码不可经此接口变更） */
@Data
@Schema(description = "更新系统用户请求")
public class UpdateSysUserDTO {

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

    @Schema(description = "角色：SUPER_ADMIN-超级管理员, ADMIN-社区管理员, STAFF-服务人员")
    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "^(SUPER_ADMIN|ADMIN|STAFF)$", message = "角色不合法")
    private String role;
}
