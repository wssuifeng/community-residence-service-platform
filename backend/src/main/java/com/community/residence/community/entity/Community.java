package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 社区信息（community 表，全局运营主体，数据级权限的锚点表） */
@Data
@TableName("community")
@Schema(description = "社区信息")
public class Community {

    @TableId(type = IdType.AUTO)
    @Schema(description = "社区ID")
    private Long id;

    @Schema(description = "社区名称")
    private String name;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "联系人")
    private String contactPerson;

    @Schema(description = "社区简介")
    private String description;

    @Schema(description = "状态：ACTIVE-运营中, INACTIVE-已停用")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
