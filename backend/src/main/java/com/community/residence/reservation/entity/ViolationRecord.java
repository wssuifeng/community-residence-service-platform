package com.community.residence.reservation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 违约处置记录（violation_record 表；预约未到/看房未到处置留痕） */
@Data
@TableName("violation_record")
@Schema(description = "违约处置记录")
public class ViolationRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "违约用户ID")
    private Long userId;

    @Schema(description = "违约类型：RESERVATION_NO_SHOW-预约未到, VIEWING_NO_SHOW-看房未到")
    private String violationType;

    @Schema(description = "关联ID（预约ID或看房ID）")
    private Long relatedId;

    @Schema(description = "处置措施")
    private String punishment;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "记录时间")
    private LocalDateTime createdAt;
}
