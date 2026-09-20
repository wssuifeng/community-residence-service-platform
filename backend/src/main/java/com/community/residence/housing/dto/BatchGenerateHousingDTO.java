package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/** 按社区批量挂牌请求（R62：为无房源房屋批量生成 AVAILABLE 房源，默认参数化） */
@Data
@Schema(description = "按社区批量挂牌请求")
public class BatchGenerateHousingDTO {

    @Schema(description = "社区ID")
    @NotNull(message = "社区ID不能为空")
    private Long communityId;

    @Schema(description = "默认月租金")
    @NotNull(message = "月租金不能为空")
    @DecimalMin(value = "0", message = "月租金不能为负数")
    private BigDecimal monthlyRent;

    @Schema(description = "默认押金（可空=面议）")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    @Schema(description = "租售类型：RENT-出租, SALE-出售（为空默认 RENT）")
    @Pattern(regexp = "^(RENT|SALE)$", message = "租售类型仅支持 RENT/SALE")
    private String rentType;

    @Schema(description = "限定楼栋ID列表（可空=整个社区；与 unitIds/houseIds 可叠加取更细粒度）")
    private List<Long> buildingIds;

    @Schema(description = "限定单元ID列表（可空=不限单元）")
    private List<Long> unitIds;

    @Schema(description = "限定房屋ID列表（可空=不限房屋；最细粒度，必须属于该社区）")
    private List<Long> houseIds;

    @Schema(description = "标题后缀（可空；非空时标题为「...·精装房源·{suffix}」，≤32 字）")
    @Size(max = 32, message = "标题后缀最多 32 字")
    private String titleSuffix;

    @Schema(description = "是否同时建立可预约看房时段（可空；未传视为 true 即默认勾选）")
    private Boolean createTimeslots;

    @Schema(description = "时段来源：DEFAULT-默认时段（周一至周五 09:00-12:00 + 14:00-18:00）, "
            + "CUSTOM-自定义时段（可空，默认 DEFAULT）")
    @Pattern(regexp = "^(DEFAULT|CUSTOM)$", message = "时段来源仅支持 DEFAULT/CUSTOM")
    private String timeslotMode;

    @Schema(description = "自定义时段列表（timeslotMode=CUSTOM 且建时段时必填，非空）")
    @Valid
    private List<TimeslotItem> timeslots;

    /** 批量挂牌自定义看房时段项（周循环模板，与 housing_timeslot 同口径） */
    @Data
    @Schema(description = "批量挂牌自定义看房时段项")
    public static class TimeslotItem {

        @Schema(description = "星期几：1-周一, 7-周日")
        @NotNull(message = "星期几不能为空")
        @Min(value = 1, message = "星期几取值 1~7")
        @Max(value = 7, message = "星期几取值 1~7")
        private Integer dayOfWeek;

        @Schema(description = "开始时间（HH:mm 或 HH:mm:ss）")
        @NotNull(message = "开始时间不能为空")
        private LocalTime startTime;

        @Schema(description = "结束时间（HH:mm 或 HH:mm:ss，须晚于开始时间）")
        @NotNull(message = "结束时间不能为空")
        private LocalTime endTime;
    }
}
