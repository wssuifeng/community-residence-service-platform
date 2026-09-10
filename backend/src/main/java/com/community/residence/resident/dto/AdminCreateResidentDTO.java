package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员代建居民请求（R8 v1.2；初始密码服务端生成：手机号后 6 位，明文仅本次返回） */
@Data
@Schema(description = "管理员代建居民请求")
public class AdminCreateResidentDTO {

    @Schema(description = "所属社区ID（数据级权限锚点，ADMIN 限绑定社区）")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "真实姓名")
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 50, message = "真实姓名最多 50 字符")
    private String realName;

    @Schema(description = "手机号")
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "身份证号")
    @Pattern(regexp = "^\\d{17}[\\dXx]$", message = "身份证号格式不正确")
    private String idCard;

    @Schema(description = "用户名（为空时服务端按手机号生成）")
    @Size(min = 4, max = 20, message = "用户名长度 4~20 字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅支持字母、数字、下划线")
    private String username;
}
