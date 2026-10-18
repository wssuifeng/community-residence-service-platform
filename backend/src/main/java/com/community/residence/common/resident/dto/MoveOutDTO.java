package com.community.residence.common.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 办理迁出请求（居住关系在住 → 已迁出） */
@Data
@Schema(description = "办理迁出请求")
public class MoveOutDTO {
    @Schema(description="明确确认同步结束主关系关联的依赖居住关系")
    private Boolean confirmDependents;


    @Schema(description = "迁出日期")
    @NotNull(message = "迁出日期不能为空")
    private LocalDate moveOutDate;

    @Schema(description = "迁出原因")
    @Size(max = 200, message = "迁出原因最多 200 字符")
    private String reason;
}
