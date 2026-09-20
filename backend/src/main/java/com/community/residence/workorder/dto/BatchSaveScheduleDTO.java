package com.community.residence.workorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

/** 排班批量设置请求（人员×日期 笛卡尔积逐条覆盖式保存） */
@Data
@Schema(description = "排班批量设置请求")
public class BatchSaveScheduleDTO {

    @Schema(description = "排班社区ID")
    @NotNull(message = "社区不能为空")
    private Long communityId;

    @Schema(description = "服务人员ID列表")
    @NotEmpty(message = "服务人员不能为空")
    private List<Long> staffIds;

    @Schema(description = "排班日期列表（yyyy-MM-dd）")
    @NotEmpty(message = "排班日期不能为空")
    private List<String> dates;

    @Schema(description = "班次：MORNING-早班, AFTERNOON-午班, EVENING-晚班, FULL-全天, REST-休息")
    @NotBlank(message = "班次类型不能为空")
    private String shiftType;

    @Schema(description = "自定义开始时间（可空=用班次默认；REST 忽略）")
    private LocalTime startTime;

    @Schema(description = "自定义结束时间（可空=用班次默认；REST 忽略）")
    private LocalTime endTime;

    @Schema(description = "备注（代班/调休说明）")
    @Size(max = 255, message = "备注最多 255 字符")
    private String remark;
}
