package com.community.residence.common.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;

/** 管理员代建居民请求（R8 v1.2；初始密码服务端生成：手机号后 6 位，明文仅本次返回） */
@Data
@Schema(description = "管理员代建居民请求")
public class AdminCreateResidentDTO {
    @Schema(description="依赖的主居住关系ID；同住关系引用同房屋有效OWNER/TENANT")
    private Long anchorRelationId;


    @Schema(description = "所属小区ID（数据级权限锚点，ADMIN 限绑定小区）")
    @NotNull(message = "所属小区不能为空")
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
    @Size(min = 1, max = 50, message = "用户名长度1～50字符")
    @Pattern(regexp = "^[\\p{L}\\p{N}_]+$", message = "用户名支持中文、字母、数字、下划线，不支持空格或特殊符号")
    private String username;

    @Schema(description = "邮箱（沿居民账号资料模型）")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱最多100字符")
    private String email;

    @Schema(description = "同时登记关系的房屋ID；空为仅创建账号")
    @Positive(message = "房屋ID必须为正整数")
    private Long houseId;

    @Schema(description = "关系类型：OWNER/TENANT/FAMILY，与houseId同时填写")
    @Pattern(regexp = "^(OWNER|TENANT|FAMILY|CO_RESIDENT)$", message = "关系类型取值OWNER/TENANT/FAMILY/CO_RESIDENT")
    private String relationType;

    @Schema(description = "入住日期，与houseId同时填写")
    @PastOrPresent(message = "入住日期不能晚于今天")
    private LocalDate moveInDate;

    @Schema(description = "居住关系备注（审计留痕）")
    @Size(max = 200, message = "备注最多200字符")
    private String remark;
}
