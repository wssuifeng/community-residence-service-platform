package com.community.residence.common.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/** 代建服务人员账号；社区绑定与账号在同一事务提交。 */
@Data
@Schema(description = "代建服务人员账号")
public class CreateStaffAccountDTO {
    @Schema(description = "用户名，1至50位文字、数字或下划线")
    @NotBlank @Size(min = 1, max = 50) @Pattern(regexp = "^[\\p{L}\\p{N}_]+$")
    private String username;
    @Schema(description = "初始密码")
    @NotBlank @Size(min = 8, max = 20)
    private String password;
    @Schema(description = "真实姓名")
    @NotBlank @Size(max = 50)
    private String realName;
    @Schema(description = "手机号")
    @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$")
    private String phone;
    @Schema(description = "邮箱")
    @Email @Size(max = 100)
    private String email;
    @Schema(description = "固定STAFF，不允许创建其他角色")
    @NotBlank @Pattern(regexp = "^STAFF$", message = "只能代建服务人员账号")
    private String role = "STAFF";
    @Schema(description = "至少绑定一个有效且有管理权限的社区")
    @NotEmpty @Size(max = 100)
    private List<@NotNull @Positive Long> communityIds;
    @Schema(description = "所选社区内的擅长服务类别，可空")
    @Size(max = 100)
    private List<@NotNull @Positive Long> categoryIds;
}
