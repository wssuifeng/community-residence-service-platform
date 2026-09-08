package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 楼栋信息（building 表，软删除） */
@Data
@TableName("building")
@Schema(description = "楼栋信息")
public class Building {

    @TableId(type = IdType.AUTO)
    @Schema(description = "楼栋ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "楼栋名称")
    private String name;

    @Schema(description = "楼层数")
    private Integer floors;

    @Schema(description = "楼栋描述")
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
