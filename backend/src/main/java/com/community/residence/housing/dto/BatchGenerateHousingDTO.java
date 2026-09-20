package com.community.residence.housing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
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
}
