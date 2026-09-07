package com.community.residence.workorder.vo;

import com.community.residence.workorder.entity.WorkOrderProcess;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单处理时间线记录响应 */
@Data
@Schema(description = "工单处理时间线记录")
public class ProcessRecordVO {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "操作动作")
    private String action;

    @Schema(description = "变更前状态")
    private String oldStatus;

    @Schema(description = "变更后状态")
    private String newStatus;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作人类型：RESIDENT-居民, STAFF-服务人员, ADMIN-管理员")
    private String operatorType;

    @Schema(description = "处理内容")
    private String content;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;

    public static ProcessRecordVO from(WorkOrderProcess entity) {
        ProcessRecordVO vo = new ProcessRecordVO();
        vo.setId(entity.getId());
        vo.setAction(entity.getAction());
        vo.setOldStatus(entity.getOldStatus());
        vo.setNewStatus(entity.getNewStatus());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorType(entity.getOperatorType());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
