package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单附件（work_order_attachment 表；文件上传 P2 阶段接入，P1 仅建实体） */
@Data
@TableName("work_order_attachment")
@Schema(description = "工单附件")
public class WorkOrderAttachment {

    @TableId(type = IdType.AUTO)
    @Schema(description = "附件ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件URL")
    private String fileUrl;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;
}
