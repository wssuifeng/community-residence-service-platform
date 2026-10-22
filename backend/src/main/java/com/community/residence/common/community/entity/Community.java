package com.community.residence.common.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 小区信息（community 表，全局运营主体，数据级权限的锚点表） */
@Data
@TableName("community")
@Schema(description = "小区信息")
public class Community {

    @TableId(type = IdType.AUTO)
    @Schema(description = "小区ID")
    private Long id;

    @Schema(description = "小区名称")
    private String name;

    @Schema(description = "本级业务编号，与内部主键分离")
    private String businessCode;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "联系电话")
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String contactPhone;

    @Schema(description = "联系人")
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String contactPerson;

    @Schema(description = "责任人系统账号ID，必须为ADMIN")
    @com.baomidou.mybatisplus.annotation.TableField(updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS)
    private Long responsibleAdminId;

    @Schema(description = "小区简介")
    private String description;

    @Schema(description = "状态：ACTIVE-运营中, INACTIVE-已停用")
    private String status;

    @Schema(description = "入住申请自动通过：0-关闭（人工审核）, 1-开启（提交即通过）")
    private Integer autoApproveResidence;

    @Schema(description = "自动通过时的默认租期月数（人工审核路径不使用）")
    private Integer defaultLeaseMonths;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
