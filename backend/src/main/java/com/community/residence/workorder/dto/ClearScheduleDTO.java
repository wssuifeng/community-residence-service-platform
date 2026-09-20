package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 排班范围清空请求（DELETE 查询参数绑定；staffIds 为空表示清空该社区范围内全部人员） */
@Data
@Schema(description = "排班范围清空请求")
public class ClearScheduleDTO {

    @Schema(description = "排班社区ID")
    @NotNull(message = "社区不能为空")
    private Long communityId;

    @Schema(description = "服务人员ID列表（可空=全部人员；逗号分隔传参）")
    private List<Long> staffIds;

    @Schema(description = "开始日期（yyyy-MM-dd）")
    @NotNull(message = "开始日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @Schema(description = "结束日期（yyyy-MM-dd，含当日）")
    @NotNull(message = "结束日期不能为空")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;
}
