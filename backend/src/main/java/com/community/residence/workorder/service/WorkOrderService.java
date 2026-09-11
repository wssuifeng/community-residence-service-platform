package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.common.service.FileUploadService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.entity.WorkOrderAssignment;
import com.community.residence.workorder.entity.WorkOrderAttachment;
import com.community.residence.workorder.entity.WorkOrderProcess;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.workorder.vo.AttachmentVO;
import com.community.residence.workorder.vo.ProcessRecordVO;
import com.community.residence.workorder.vo.WorkOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单业务逻辑：待受理→待派单→已派单→已接单→处理中→待确认→已完成
 * + 已关闭/已驳回/已取消（六大状态机 #1）。
 * 每次流转写 work_order_process 时间线；派单写 work_order_assignment。
 * STAFF 维度数据权限（限派给本人）在查询层显式约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderService {

    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 状态机合法流转表（key 当前状态 → value 可达状态集合）。
        权威口径见架构设计 §6.1 / 01_工单状态机.lifecycle.json：
        居民取消仅 PENDING/TO_ASSIGN 可发起；派单限 PENDING/TO_ASSIGN/ASSIGNED
        （ASSIGNED=改派）；TO_CONFIRM → IN_PROGRESS 为居民不满意退回（DEF-019 端点） */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            WorkOrderStatus.PENDING, Set.of(WorkOrderStatus.TO_ASSIGN, WorkOrderStatus.REJECTED,
                    WorkOrderStatus.CANCELLED, WorkOrderStatus.ASSIGNED),
            WorkOrderStatus.TO_ASSIGN, Set.of(WorkOrderStatus.ASSIGNED, WorkOrderStatus.REJECTED,
                    WorkOrderStatus.CANCELLED),
            WorkOrderStatus.ASSIGNED, Set.of(WorkOrderStatus.ACCEPTED, WorkOrderStatus.ASSIGNED),
            WorkOrderStatus.ACCEPTED, Set.of(WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.IN_PROGRESS, Set.of(WorkOrderStatus.TO_CONFIRM),
            WorkOrderStatus.TO_CONFIRM, Set.of(WorkOrderStatus.COMPLETED, WorkOrderStatus.IN_PROGRESS),
            WorkOrderStatus.COMPLETED, Set.of(WorkOrderStatus.CLOSED),
            WorkOrderStatus.CLOSED, Set.of(),
            WorkOrderStatus.REJECTED, Set.of(),
            WorkOrderStatus.CANCELLED, Set.of());

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderProcessMapper processMapper;
    private final WorkOrderAssignmentMapper assignmentMapper;
    private final WorkOrderAttachmentMapper attachmentMapper;
    private final ServiceCategoryMapper categoryMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final FileUploadService fileUploadService;

    /* 提交工单：初始 PENDING 待受理；工单号 WO+日期+随机序号 */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO create(CreateWorkOrderDTO dto) {
        ServiceCategory category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null || category.getIsActive() != 1) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务类别不存在或已停用");
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(generateOrderNo());
        order.setResidentId(SecurityUtils.getUserId());
        order.setCommunityId(category.getCommunityId());
        order.setCategoryId(category.getId());
        order.setTitle(dto.getTitle());
        order.setContent(dto.getContent());
        order.setContactPhone(dto.getContactPhone());
        order.setAddress(dto.getAddress());
        order.setStatus(WorkOrderStatus.PENDING);
        order.setPriority(StringUtils.hasText(dto.getPriority()) ? dto.getPriority() : "NORMAL");
        workOrderMapper.insert(order);

        appendProcess(order.getId(), "SUBMIT", null, WorkOrderStatus.PENDING, dto.getContent());
        return toVO(order);
    }

    /** 居民更新工单：仅 PENDING/TO_ASSIGN 可改本人工单 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "WORK_ORDER", targetId = "#id", content = "'居民修改工单：' + #dto.title")
    public WorkOrderVO update(Long id, CreateWorkOrderDTO dto) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        if (!WorkOrderStatus.PENDING.equals(order.getStatus())
                && !WorkOrderStatus.TO_ASSIGN.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "工单已被受理，不可修改");
        }
        order.setTitle(dto.getTitle());
        order.setContent(dto.getContent());
        order.setContactPhone(dto.getContactPhone());
        order.setAddress(dto.getAddress());
        if (StringUtils.hasText(dto.getPriority())) {
            order.setPriority(dto.getPriority());
        }
        workOrderMapper.updateById(order);
        return toVO(order);
    }

    /** 工单详情：RESIDENT 限本人；STAFF 限派给本人；ADMIN 拦截器过滤 */
    public WorkOrderVO getById(Long id) {
        WorkOrder order = requireOrder(id);
        checkReadAccess(order);
        return toVO(order);
    }

    /* 附件列表：访问权限与详情同口径 */
    public List<AttachmentVO> attachments(Long orderId) {
        WorkOrder order = requireOrder(orderId);
        checkReadAccess(order);
        return attachmentMapper.selectList(new LambdaQueryWrapper<WorkOrderAttachment>()
                        .eq(WorkOrderAttachment::getWorkOrderId, orderId)
                        .orderByAsc(WorkOrderAttachment::getId))
                .stream().map(AttachmentVO::from).toList();
    }

    /* 上传工单附件（接口设计.md 9.4.3.1）：居民限提交人，服务人员限被派单人
       （处理现场照片场景，BE-ISSUE-10），管理员放行（数据级权限已过滤）；
       文件落盘后建附件记录 */
    @Transactional(rollbackFor = Exception.class)
    public AttachmentVO uploadAttachment(Long orderId, MultipartFile file) {
        WorkOrder order = requireOrder(orderId);
        checkReadAccess(order);
        FileUploadService.UploadResult uploaded = fileUploadService.uploadAutoType(file);
        WorkOrderAttachment attachment = new WorkOrderAttachment();
        attachment.setWorkOrderId(orderId);
        attachment.setFileName(uploaded.fileName());
        attachment.setFileUrl(uploaded.fileUrl());
        attachment.setFileType(uploaded.fileType());
        attachment.setFileSize(uploaded.fileSize());
        attachmentMapper.insert(attachment);
        return AttachmentVO.from(attachment);
    }

    /* 删除工单附件（接口设计.md 9.4.3.2）：访问权限与上传同口径；物理文件保留（P2 异步清理） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttachment(Long attachmentId) {
        WorkOrderAttachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null) {
            throw new ResourceNotFoundException("附件不存在");
        }
        WorkOrder order = requireOrder(attachment.getWorkOrderId());
        checkReadAccess(order);
        attachmentMapper.deleteById(attachmentId);
    }

    public PageVO<WorkOrderVO> page(long page, long size, String status, String priority,
                                    Long categoryId, String keyword) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .eq(StringUtils.hasText(status), WorkOrder::getStatus, status)
                .eq(StringUtils.hasText(priority), WorkOrder::getPriority, priority)
                .eq(categoryId != null, WorkOrder::getCategoryId, categoryId)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(WorkOrder::getTitle, keyword)
                        .or().like(WorkOrder::getContent, keyword))
                .orderByDesc(WorkOrder::getId);
        /* RESIDENT 限本人；STAFF 限派给本人（工单表无法直查派单表，先取派单ID集合） */
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(WorkOrder::getResidentId, SecurityUtils.getUserId());
        } else if (SecurityUtils.hasRole(RoleConstants.STAFF)) {
            List<Long> orderIds = assignmentMapper.selectList(
                            new LambdaQueryWrapper<WorkOrderAssignment>()
                                    .eq(WorkOrderAssignment::getAssigneeId, SecurityUtils.getUserId()))
                    .stream().map(WorkOrderAssignment::getWorkOrderId).distinct().toList();
            if (orderIds.isEmpty()) {
                return PageVO.of(List.of(), 0, page, size);
            }
            wrapper.in(WorkOrder::getId, orderIds);
        }
        Page<WorkOrder> result = workOrderMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 派单：PENDING/TO_ASSIGN → ASSIGNED；ASSIGNED → ASSIGNED 为改派（重新派单覆盖
       当前处理人）；已接单及之后不可改派（架构 §6.1，DEF-012） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'派单给服务人员 ' + #dto.assigneeId")
    public void assign(Long id, AssignWorkOrderDTO dto) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        SysUser assignee = sysUserMapper.selectById(dto.getAssigneeId());
        if (assignee == null || !RoleConstants.STAFF.equals(assignee.getRole())) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务人员不存在");
        }
        if (!"ACTIVE".equals(assignee.getStatus())) {
            throw new BusinessException(ErrorCode.ACCOUNT_FROZEN, "服务人员账号已冻结");
        }
        String oldStatus = order.getStatus();
        /* 与 ALLOWED_TRANSITIONS 同口径：PENDING/TO_ASSIGN 首派 + ASSIGNED 改派 */
        Set<String> assignable = Set.of(WorkOrderStatus.PENDING, WorkOrderStatus.TO_ASSIGN,
                WorkOrderStatus.ASSIGNED);
        if (!assignable.contains(oldStatus)) {
            throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION,
                    String.format("仅待受理/待派单/已派单（改派）状态可派单，当前 %s", oldStatus));
        }
        order.setStatus(WorkOrderStatus.ASSIGNED);
        workOrderMapper.updateById(order);

        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(id);
        assignment.setAssigneeId(dto.getAssigneeId());
        assignment.setAssignerId(SecurityUtils.getUserId());
        assignment.setAssignTime(LocalDateTime.now());
        assignmentMapper.insert(assignment);

        appendProcess(id, "ASSIGN", oldStatus, WorkOrderStatus.ASSIGNED,
                "派单给 " + assignee.getRealName() + (dto.getRemark() != null ? "：" + dto.getRemark() : ""));
        /* 通知双向触达：服务人员接单提醒 + 居民派单进度 */
        notificationService.create(dto.getAssigneeId(), order.getCommunityId(), "新工单派发",
                "工单 " + order.getOrderNo() + " 已派单给您，请及时接单",
                "WORK_ORDER", "WORK_ORDER", id);
        notificationService.create(order.getResidentId(), order.getCommunityId(), "工单已派单",
                "您的工单 " + order.getOrderNo() + " 已派单给 " + assignee.getRealName(),
                "WORK_ORDER", "WORK_ORDER", id);
        log.info("工单已派单：orderId={}, assigneeId={}, operator={}",
                id, dto.getAssigneeId(), SecurityUtils.getUserId());
    }

    /* 接单：ASSIGNED → ACCEPTED；仅被派单的服务人员 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'服务人员接单'")
    public void accept(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        transition(order, WorkOrderStatus.ACCEPTED);
        fillAcceptTime(id);
        appendProcess(id, "ACCEPT", WorkOrderStatus.ASSIGNED, WorkOrderStatus.ACCEPTED, remark);
    }

    /* 开始处理：ACCEPTED → IN_PROGRESS */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'开始处理工单'")
    public void process(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        transition(order, WorkOrderStatus.IN_PROGRESS);
        appendProcess(id, "PROCESS", WorkOrderStatus.ACCEPTED, WorkOrderStatus.IN_PROGRESS, remark);
    }

    /* 完成处理：IN_PROGRESS → TO_CONFIRM（待居民确认） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'提交处理结果'")
    public void complete(Long id, String solution) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        transition(order, WorkOrderStatus.TO_CONFIRM);
        appendProcess(id, "COMPLETE", WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.TO_CONFIRM, solution);
        notificationService.create(order.getResidentId(), order.getCommunityId(), "工单已处理完成",
                "您的工单 " + order.getOrderNo() + " 已处理完成，请确认", "WORK_ORDER", "WORK_ORDER", id);
    }

    /* 居民确认：TO_CONFIRM → COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'居民确认完成'")
    public void confirm(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        transition(order, WorkOrderStatus.COMPLETED);
        appendProcess(id, "CONFIRM", WorkOrderStatus.TO_CONFIRM, WorkOrderStatus.COMPLETED, remark);
        notifyCurrentAssignee(order, "居民已确认工单",
                "工单 " + order.getOrderNo() + " 已被居民确认完成", id);
    }

    /* 关闭工单：COMPLETED → CLOSED（终态） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'管理员关闭工单'")
    public void close(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        transition(order, WorkOrderStatus.CLOSED);
        appendProcess(id, "CLOSE", WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED, remark);
    }

    /* 驳回：PENDING/TO_ASSIGN → REJECTED（终态） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'驳回工单：' + #reason")
    public void reject(Long id, String reason) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        transition(order, WorkOrderStatus.REJECTED);
        appendProcess(id, "REJECT", order.getStatus(), WorkOrderStatus.REJECTED, reason);
    }

    /* 取消：仅 PENDING/TO_ASSIGN 居民可取消（R19/架构 §6.1，流转表拦截其余状态，DEF-012） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'居民取消工单：' + #reason")
    public void cancel(Long id, String reason) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        transition(order, WorkOrderStatus.CANCELLED);
        appendProcess(id, "CANCEL", order.getStatus(), WorkOrderStatus.CANCELLED, reason);
    }

    /** 处理时间线（按时间升序） */
    public List<ProcessRecordVO> timeline(Long id) {
        WorkOrder order = requireOrder(id);
        checkReadAccess(order);
        return processMapper.selectList(new LambdaQueryWrapper<WorkOrderProcess>()
                        .eq(WorkOrderProcess::getWorkOrderId, id)
                        .orderByAsc(WorkOrderProcess::getId))
                .stream().map(this::toProcessVO).toList();
    }

    public WorkOrder requireOrder(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new ResourceNotFoundException("工单不存在");
        }
        return order;
    }

    /* 状态机校验 + 主表更新（order.status 已被改写前保存原值） */
    private void transition(WorkOrder order, String target) {
        String current = order.getStatus();
        /* DEF-011：同态重复动作与非法流转同口径拒绝（R19/R20/R22：
           已接单不可被再接、已完成不可再确认），且不产生处理记录 */
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION,
                    String.format("工单状态不允许从 %s 流转到 %s", current, target));
        }
        order.setStatus(target);
        workOrderMapper.updateById(order);
    }

    private void appendProcess(Long orderId, String action, String oldStatus,
                               String newStatus, String content) {
        WorkOrderProcess process = new WorkOrderProcess();
        process.setWorkOrderId(orderId);
        process.setOperatorId(SecurityUtils.getUserId());
        process.setOperatorType(resolveOperatorType());
        process.setAction(action);
        process.setOldStatus(oldStatus);
        process.setNewStatus(newStatus);
        process.setContent(content);
        processMapper.insert(process);
    }

    /** 通知当前派单处理人（接单/确认等 STAFF 触达场景；无派单记录时静默跳过） */
    private void notifyCurrentAssignee(WorkOrder order, String title, String content, Long orderId) {
        WorkOrderAssignment latest = assignmentMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getWorkOrderId, orderId)
                        .orderByDesc(WorkOrderAssignment::getId)
                        .last("LIMIT 1"));
        if (latest != null) {
            notificationService.create(latest.getAssigneeId(), order.getCommunityId(),
                    title, content, "WORK_ORDER", "WORK_ORDER", orderId);
        }
    }

    private String resolveOperatorType() {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            return "RESIDENT";
        }
        if (SecurityUtils.hasRole(RoleConstants.STAFF)) {
            return "STAFF";
        }
        return "ADMIN";
    }

    /* STAFF 操作权限：须是当前派单记录的处理人 */
    private void checkAssignee(WorkOrder order) {
        WorkOrderAssignment latest = assignmentMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getWorkOrderId, order.getId())
                        .orderByDesc(WorkOrderAssignment::getId)
                        .last("LIMIT 1"));
        if (latest == null || !latest.getAssigneeId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅被派单的服务人员可操作该工单");
        }
    }

    private void checkResidentOwner(WorkOrder order) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !order.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅工单提交人可操作");
        }
    }

    private void checkReadAccess(WorkOrder order) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            checkResidentOwner(order);
        } else if (SecurityUtils.hasRole(RoleConstants.STAFF)) {
            checkAssignee(order);
        }
    }

    private void fillAcceptTime(Long orderId) {
        WorkOrderAssignment latest = assignmentMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getWorkOrderId, orderId)
                        .orderByDesc(WorkOrderAssignment::getId)
                        .last("LIMIT 1"));
        if (latest != null) {
            latest.setAcceptTime(LocalDateTime.now());
            assignmentMapper.updateById(latest);
        }
    }

    /** 工单号：WO + yyyyMMdd + 4 位随机序号（单机日提交量远小于随机空间，重复由唯一键兜底重试） */
    private String generateOrderNo() {
        return "WO" + LocalDate.now().format(ORDER_NO_DATE)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private WorkOrderVO toVO(WorkOrder order) {
        WorkOrderVO vo = WorkOrderVO.from(order);
        Resident resident = residentMapper.selectById(order.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        ServiceCategory category = categoryMapper.selectById(order.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getName());
        }
        WorkOrderAssignment latest = assignmentMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getWorkOrderId, order.getId())
                        .orderByDesc(WorkOrderAssignment::getId)
                        .last("LIMIT 1"));
        if (latest != null) {
            vo.setAssigneeId(latest.getAssigneeId());
            SysUser assignee = sysUserMapper.selectById(latest.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeName(assignee.getRealName());
            }
        }
        return vo;
    }

    private ProcessRecordVO toProcessVO(WorkOrderProcess process) {
        ProcessRecordVO vo = ProcessRecordVO.from(process);
        if ("RESIDENT".equals(process.getOperatorType())) {
            Resident resident = residentMapper.selectById(process.getOperatorId());
            if (resident != null) {
                vo.setOperatorName(resident.getRealName());
            }
        } else {
            SysUser user = sysUserMapper.selectById(process.getOperatorId());
            if (user != null) {
                vo.setOperatorName(user.getRealName());
            }
        }
        return vo;
    }
}
