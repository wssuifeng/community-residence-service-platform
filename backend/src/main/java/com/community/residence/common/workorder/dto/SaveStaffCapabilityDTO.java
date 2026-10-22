package com.community.residence.common.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import jakarta.validation.constraints.*;

import java.util.List;

/** 服务人员能力绑定保存请求（全量覆盖式：为空或不传表示清空该维度绑定） */
@Data
@Schema(description = "服务人员能力绑定保存请求")
public class SaveStaffCapabilityDTO {
    @Schema(description = "姓名，省略保持不变")
    @Size(min = 1, max = 50) @Pattern(regexp = ".*\\S.*", message = "姓名不能为空白")
    private String realName;
    @Schema(description = "手机号，省略保持不变")
    @Pattern(regexp = "^1[3-9]\\d{9}$")
    private String phone;
    @Schema(description = "邮箱，省略保持不变")
    @Email @Size(max = 100)
    private String email;


    @Schema(description = "常驻小区ID列表（可空=清空小区绑定）")
    private List<Long> communityIds;

    @Schema(description = "擅长服务类别ID列表（可空=清空类别绑定）")
    private List<Long> categoryIds;
}
