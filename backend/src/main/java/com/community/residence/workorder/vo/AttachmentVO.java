package com.community.residence.workorder.vo;

import com.community.residence.workorder.entity.WorkOrderAttachment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单附件响应（接口设计.md 9.4.3.3；文件上传 P2 接入，P1 列表可为空） */
@Data
@Schema(description = "工单附件")
public class AttachmentVO {

    @Schema(description = "附件ID")
    private Long id;

    @Schema(description = "工单ID")
    private Long workOrderId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件URL")
    private String fileUrl;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    public static AttachmentVO from(WorkOrderAttachment entity) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(entity.getId());
        vo.setWorkOrderId(entity.getWorkOrderId());
        vo.setFileName(entity.getFileName());
        vo.setFileUrl(entity.getFileUrl());
        vo.setFileSize(entity.getFileSize());
        vo.setFileType(entity.getFileType());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
