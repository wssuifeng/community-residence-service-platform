package com.community.residence.common.workorder.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.entity.StaffCommunity;
import com.community.residence.common.workorder.entity.StaffSchedule;
import com.community.residence.common.workorder.entity.StaffServiceCategory;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.entity.WorkOrderAttachment;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.StaffCommunityMapper;
import com.community.residence.common.workorder.mapper.StaffScheduleMapper;
import com.community.residence.common.workorder.mapper.StaffServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 社区删除时的workorder域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityWorkOrderCleanupService {
    private final ServiceCategoryMapper serviceCategoryMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderAttachmentMapper workOrderAttachmentMapper;
    private final WorkOrderProcessMapper workOrderProcessMapper;
    private final WorkOrderAssignmentMapper workOrderAssignmentMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final StaffScheduleMapper staffScheduleMapper;
    private final StaffServiceCategoryMapper staffServiceCategoryMapper;
    private final com.community.residence.common.evaluation.service.CommunityEvaluationCleanupService evaluationCleanup;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeOrders(Long id) {
        /* ---- 工单链（V1 #15~18 + #27/28）：附件/处理记录/派单 → 评价/跟进 → 工单 → 服务类别 ---- */
        List<Long> orderIds = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>().eq(WorkOrder::getCommunityId, id))
                .stream().map(WorkOrder::getId).toList();
        if (!orderIds.isEmpty()) {
            workOrderAttachmentMapper.delete(new LambdaQueryWrapper<WorkOrderAttachment>()
                    .in(WorkOrderAttachment::getWorkOrderId, orderIds));
            workOrderProcessMapper.delete(new LambdaQueryWrapper<WorkOrderProcess>()
                    .in(WorkOrderProcess::getWorkOrderId, orderIds));
            workOrderAssignmentMapper.delete(new LambdaQueryWrapper<WorkOrderAssignment>()
                    .in(WorkOrderAssignment::getWorkOrderId, orderIds));
            evaluationCleanup.removeForOrders(orderIds);
            workOrderMapper.deleteBatchIds(orderIds);
        }
        /* 服务类别删除前先解除「人员擅长类别」绑定（V19 staff_service_category 外键指向类别） */
        List<Long> categoryIds = serviceCategoryMapper.selectList(
                new LambdaQueryWrapper<ServiceCategory>().eq(ServiceCategory::getCommunityId, id))
                .stream().map(ServiceCategory::getId).toList();
        if (!categoryIds.isEmpty()) {
            staffServiceCategoryMapper.delete(new LambdaQueryWrapper<StaffServiceCategory>()
                    .in(StaffServiceCategory::getCategoryId, categoryIds));
        }
        serviceCategoryMapper.delete(new LambdaQueryWrapper<ServiceCategory>()
                .eq(ServiceCategory::getCommunityId, id));

    }
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeStaffBindings(Long id) {
        /* V19 人员绑定/排班 + V18 协议模板：直接以 community_id 归属 */
        staffCommunityMapper.delete(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getCommunityId, id));
        staffScheduleMapper.delete(new LambdaQueryWrapper<StaffSchedule>()
                .eq(StaffSchedule::getCommunityId, id));
    }
}
