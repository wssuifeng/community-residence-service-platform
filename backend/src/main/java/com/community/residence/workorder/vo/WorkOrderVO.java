package com.community.residence.workorder.vo;

import com.community.residence.workorder.entity.WorkOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工单响应 */
@Data
@Schema(description = "工单")
public class WorkOrderVO {

    @Schema(description = "工单ID")
    private Long id;

    @Schema(description = "工单编号")
    private String orderNo;

    @Schema(description = "提交人ID")
    private Long residentId;

    @Schema(description = "提交人姓名")
    private String residentName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "服务类别ID")
    private Long categoryId;

    @Schema(description = "服务类别名称")
    private String categoryName;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "服务地址")
    private String address;

    @Schema(description = "状态：PENDING-待受理, TO_ASSIGN-待派单, ASSIGNED-已派单, ACCEPTED-已接单, IN_PROGRESS-处理中, TO_CONFIRM-待确认, COMPLETED-已完成, CLOSED-已关闭, REJECTED-已驳回, CANCELLED-已取消")
    private String status;

    @Schema(description = "优先级：LOW/NORMAL/HIGH/URGENT")
    private String priority;

    @Schema(description = "当前处理人ID（未派单为空）")
    private Long assigneeId;

    @Schema(description = "当前处理人姓名")
    private String assigneeName;

    @Schema(description = "提交时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static WorkOrderVO from(WorkOrder entity) {
        WorkOrderVO vo = new WorkOrderVO();
        vo.setId(entity.getId());
        vo.setOrderNo(entity.getOrderNo());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setCategoryId(entity.getCategoryId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setContactPhone(entity.getContactPhone());
        vo.setAddress(entity.getAddress());
        vo.setStatus(entity.getStatus());
        vo.setPriority(entity.getPriority());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
