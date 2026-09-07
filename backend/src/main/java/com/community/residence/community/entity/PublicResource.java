package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公共资源（public_resource 表，软删除） */
@Data
@TableName("public_resource")
@Schema(description = "公共资源")
public class PublicResource {

    @TableId(type = IdType.AUTO)
    @Schema(description = "资源ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "资源名称")
    private String name;

    @Schema(description = "资源类型：MEETING_ROOM-会议室, GYM-健身房, PARKING-停车位")
    private String type;

    @Schema(description = "位置描述")
    private String location;

    @Schema(description = "容纳人数/车位数")
    private Integer capacity;

    @Schema(description = "资源描述")
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
