package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 服务人员能力绑定保存请求（全量覆盖式：为空或不传表示清空该维度绑定） */
@Data
@Schema(description = "服务人员能力绑定保存请求")
public class SaveStaffCapabilityDTO {

    @Schema(description = "常驻社区ID列表（可空=清空社区绑定）")
    private List<Long> communityIds;

    @Schema(description = "擅长服务类别ID列表（可空=清空类别绑定）")
    private List<Long> categoryIds;
}
