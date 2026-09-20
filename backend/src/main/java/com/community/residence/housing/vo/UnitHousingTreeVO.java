package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 单元节点（R62 房源社区一体化树） */
@Data
@Schema(description = "单元节点（含房屋房源树）")
public class UnitHousingTreeVO {

    @Schema(description = "单元ID")
    private Long id;

    @Schema(description = "单元名称")
    private String name;

    @Schema(description = "房屋列表")
    private List<HouseHousingTreeVO> houses;
}
