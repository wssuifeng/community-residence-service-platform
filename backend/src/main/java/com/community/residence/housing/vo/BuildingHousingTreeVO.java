package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 楼栋节点（R62 房源社区一体化树：楼栋→单元→房屋→房源摘要） */
@Data
@Schema(description = "楼栋节点（含房屋房源树）")
public class BuildingHousingTreeVO {

    @Schema(description = "楼栋ID")
    private Long id;

    @Schema(description = "楼栋名称")
    private String name;

    @Schema(description = "单元列表")
    private List<UnitHousingTreeVO> units;
}
