package com.community.residence.workorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 服务人员能力绑定响应（常驻社区 + 擅长类别） */
@Data
@Schema(description = "服务人员能力绑定")
public class StaffCapabilityVO {

    @Schema(description = "服务人员ID（sys_user STAFF 账号ID）")
    private Long staffId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "账号状态：ACTIVE-正常, FROZEN-冻结")
    private String status;

    @Schema(description = "常驻社区ID列表")
    private List<Long> communityIds;

    @Schema(description = "常驻社区名称列表（与 communityIds 同序）")
    private List<String> communityNames;

    @Schema(description = "擅长服务类别ID列表")
    private List<Long> categoryIds;

    @Schema(description = "擅长服务类别名称列表（与 categoryIds 同序）")
    private List<String> categoryNames;
}
