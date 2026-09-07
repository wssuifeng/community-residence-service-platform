package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 房屋状态变更历史（house_status_history 表，仅追加） */
@Data
@TableName("house_status_history")
@Schema(description = "房屋状态变更历史")
public class HouseStatusHistory {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "变更前状态")
    private String oldStatus;

    @Schema(description = "变更后状态")
    private String newStatus;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "变更备注")
    private String remark;

    @Schema(description = "变更时间")
    private LocalDateTime createdAt;
}
