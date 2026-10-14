package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 退租申请审核通过请求（R70：房源状态回收由管理端选择，缺省回到在架可租） */
@Data
@Schema(description = "退租申请审核通过请求")
public class ApproveMoveOutDTO {
    @Schema(description="明确确认同步结束主关系关联的依赖居住关系")
    private Boolean confirmDependents;


    @Schema(description = "房源状态回收：AVAILABLE-在架可租（默认）, OFFLINE-下架")
    @Pattern(regexp = "^(AVAILABLE|OFFLINE)$", message = "房源状态只支持 AVAILABLE / OFFLINE")
    private String housingStatus;

    @Schema(description = "审核备注")
    private String remark;
}
