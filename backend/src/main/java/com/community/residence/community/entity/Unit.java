package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 单元信息（unit 表，冗余 community_id 供数据级权限过滤，软删除） */
@Data
@TableName("unit")
@Schema(description = "单元信息")
public class Unit {

    @TableId(type = IdType.AUTO)
    @Schema(description = "单元ID")
    private Long id;

    @Schema(description = "所属楼栋ID")
    private Long buildingId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "单元名称")
    private String name;

    @Schema(description = "单元描述")
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
