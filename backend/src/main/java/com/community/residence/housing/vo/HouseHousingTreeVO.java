package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 房屋节点（R62 房源社区一体化树：housing 为空表示未挂牌） */
@Data
@Schema(description = "房屋节点（含房源摘要）")
public class HouseHousingTreeVO {

    @Schema(description = "房屋ID")
    private Long id;

    @Schema(description = "房号")
    private String houseNumber;

    @Schema(description = "楼层")
    private Integer floor;

    @Schema(description = "建筑面积（㎡）")
    private java.math.BigDecimal area;

    @Schema(description = "户型")
    private String layout;

    @Schema(description = "房源摘要（未挂牌为 null）")
    private HousingSummaryVO housing;
}
