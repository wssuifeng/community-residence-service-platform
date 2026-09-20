package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 房屋管理列表项（接口设计.md 9.15.3）：以房屋为主线，挂牌信息为可选子对象
 * （未挂牌 housing 为 null），供管理端「房屋与房源」带图卡片 + 筛选布局使用。
 */
@Data
@Schema(description = "房屋管理列表项（含挂牌摘要）")
public class HouseManageItemVO {

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "所属社区名称")
    private String communityName;

    @Schema(description = "所属楼栋ID")
    private Long buildingId;

    @Schema(description = "所属楼栋名称")
    private String buildingName;

    @Schema(description = "所属单元ID")
    private Long unitId;

    @Schema(description = "所属单元名称")
    private String unitName;

    @Schema(description = "房号")
    private String houseNumber;

    @Schema(description = "楼层")
    private Integer floor;

    @Schema(description = "建筑面积（平方米）")
    private BigDecimal area;

    @Schema(description = "户型（如 2室1厅1卫）")
    private String layout;

    @Schema(description = "房屋状态：VACANT-空置, OCCUPIED-已入住, RESERVED-预留, MAINTENANCE-维护中")
    private String houseStatus;

    @Schema(description = "挂牌房源摘要；null=未挂牌")
    private HousingBriefVO housing;
}
