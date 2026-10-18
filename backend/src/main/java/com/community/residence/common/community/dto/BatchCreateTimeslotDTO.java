package com.community.residence.common.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalTime;
import java.util.List;

@Data
@Schema(description = "多星期周循环时段请求")
public class BatchCreateTimeslotDTO {
    @Schema(description = "星期列表，1周一至7周日")
    @NotEmpty @Size(max = 7)
    private List<@NotNull @Min(1) @Max(7) Integer> daysOfWeek;
    @Schema(description = "开始时间")
    @NotNull
    private LocalTime startTime;
    @Schema(description = "结束时间")
    @NotNull
    private LocalTime endTime;
    @Schema(description = "默认可约0或1；已有模板状态不变")
    @Min(0) @Max(1)
    private Integer isAvailable;
}
