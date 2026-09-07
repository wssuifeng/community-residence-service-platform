package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 房屋信息（house 表，V4 迁移补充 layout/orientation，软删除） */
@Data
@TableName("house")
@Schema(description = "房屋信息")
public class House {

    @TableId(type = IdType.AUTO)
    @Schema(description = "房屋ID")
    private Long id;

    @Schema(description = "所属单元ID")
    private Long unitId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房号")
    private String houseNumber;

    @Schema(description = "楼层")
    private Integer floor;

    @Schema(description = "建筑面积（平方米）")
    private BigDecimal area;

    @Schema(description = "房间数")
    private Integer roomCount;

    @Schema(description = "户型（如 2室1厅1卫）")
    private String layout;

    @Schema(description = "朝向")
    private String orientation;

    @Schema(description = "状态：VACANT-空置, OCCUPIED-已入住, RESERVED-预留, MAINTENANCE-维护中")
    private String status;

    @Schema(description = "房屋描述")
    private String description;

    @Schema(description = "软删除标记：0-未删除, 1-已删除")
    @TableLogic
    private Integer isDeleted;

    @Schema(description = "删除时间")
    private LocalDateTime deletedAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
