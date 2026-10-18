package com.community.residence.common.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.workorder.constant.DispatchFlag;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.workorder.constant.WorkOrderPriority;
import com.community.residence.common.workorder.constant.WorkOrderStatus;
import com.community.residence.common.workorder.constant.WorkOrderProcessAction;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.upload.service.FileUploadService;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.common.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.entity.WorkOrderAttachment;
import com.community.residence.common.workorder.entity.WorkOrderProcess;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import com.community.residence.common.workorder.vo.AttachmentVO;
import com.community.residence.common.workorder.vo.ProcessRecordVO;
import com.community.residence.common.workorder.vo.StaffOptionVO;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** 工单号撞号重试上限（DEF-026） */
    private static final int ORDER_NO_RETRY_LIMIT = 5;

    /** 终态集合：不在此集合即「未完结」（在手工单计数与调度标记判定） */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED,
            WorkOrderStatus.REJECTED, WorkOrderStatus.CANCELLED);

    /** 调度超时阈值（分钟）：待受理/待派单未受理、已派单未接单、处理中未完成 */
    private static final long OVERDUE_ACCEPT_MINUTES = 30;
    private static final long OVERDUE_ASSIGN_MINUTES = 120;
    private static final long OVERDUE_PROCESS_MINUTES = 1440;

    /** 新建工单标记窗口（分钟） */
    private static final long NEW_ORDER_MINUTES = 120;

    /** 列表排序取值（sort 参数）：等待时长降序、紧急优先 */
    private static final String SORT_WAIT_DESC = "WAIT_DESC";
    private static final String SORT_PRIORITY = "PRIORITY";

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
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final NotificationService notificationService;
    private final FileUploadService fileUploadService;
    private final com.community.residence.common.resident.mapper.ResidenceRelationMapper residenceRelationMapper;
    private final StaffCapabilityService staffCapabilityService;
    private final StaffScheduleService staffScheduleService;

    /** 工单临时群聊（R74）：建群 / 派单入群 / 终审回收，复用既有会话三级模型 */
    private final ConversationService conversationService;

    /* 提交工单：初始 PENDING 待受理；工单号 WO+日期+随机序号。
       R9「申请通过后获得居民端功能入口」+ R12 居住私有数据口径（DEF-001）：
       提交人须在类别归属社区存在在住关系（move_out_date 为空） */
    @Transactional(rollbackFor = Exception.class)
    public WorkOrderVO create(CreateWorkOrderDTO dto) {
        ServiceCategory category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null || category.getIsActive() != 1) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务类别不存在或已停用");
        }
        Long residentId = SecurityUtils.getUserId();
        Long activeRelation = residenceRelationMapper.selectCount(
                new LambdaQueryWrapper<com.community.residence.common.resident.entity.ResidenceRelation>()
                        .eq(com.community.residence.common.resident.entity.ResidenceRelation::getResidentId, residentId)
                        .eq(com.community.residence.common.resident.entity.ResidenceRelation::getCommunityId,
                                category.getCommunityId())
                        .isNull(com.community.residence.common.resident.entity.ResidenceRelation::getMoveOutDate)
                        .le(com.community.residence.common.resident.entity.ResidenceRelation::getMoveInDate,java.time.LocalDate.now()));
        if (activeRelation == 0) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "居住关系未建立或不在该社区，无法提交工单（请先完成入住申请）");
        }

        WorkOrder order = new WorkOrder();
        order.setOrderNo(generateOrderNo());
        order.setResidentId(residentId);
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
        /* R48/R74 事件 1「提交」：**必须**通知该社区绑定管理员与超管（DEF-072 修复口径）——
           居民提交后管理端必须"被知道"，否则受理入口对管理端不可见；居民不重复收本事件 */
        notifySubmit(order);
        return toVO(order);
    }

    /* 受理（R74 / DEF-071 裁决＝补实现，接口设计 §9.21.1）：PENDING → TO_ASSIGN；
       ADMIN/SUPER_ADMIN（数据级按社区在控制器与服务层双重收口）。
       同一事务内建立工单临时群聊（type=WORK_ORDER_GROUP、related_id=工单ID，
       成员＝居民提交人+受理管理员）并回写 work_order.conversation_id；
       重复受理/非法状态 → 5302（非成功幂等，与撤回同口径，不重复建群）。 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'管理端受理工单'")
    public WorkOrderVO intake(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        /* 并发受理：另一赢家已完成 PENDING→TO_ASSIGN 时幂等返回，不重复建群/留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.TO_ASSIGN)) {
            return toVO(order);
        }
        appendProcess(id, "INTAKE", WorkOrderStatus.PENDING, WorkOrderStatus.TO_ASSIGN, remark);

        /* 建群与流转同一事务：受理失败不建群；命中既有群直接复用（同一工单建群唯一） */
        Conversation group = conversationService.createWorkOrderGroup(id, order.getCommunityId(),
                order.getResidentId(), SecurityUtils.getUserId(), order.getOrderNo());
        if (group != null) {
            order.setConversationId(group.getId());
            workOrderMapper.updateById(order);
            conversationService.sendSystemMessage(group.getId(),
                    "工单已受理，本群用于工单沟通（工单号 " + order.getOrderNo() + "）");
        }
        /* R48/R74 事件 2「受理」：通知居民提交人 + 该社区绑定管理员 */
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单已受理",
                "您的工单 " + order.getOrderNo() + " 已被受理，正在安排服务人员",
                "WORK_ORDER", "WORK_ORDER", id);
        notifyCommunityAdmins(order, "工单已受理",
                "工单 " + order.getOrderNo() + " 已受理，待派单");
        log.info("工单已受理：orderId={}, conversationId={}, operator={}", id,
                group == null ? null : group.getId(), SecurityUtils.getUserId());
        return toVO(order);
    }

    /** 居民更新工单：仅 PENDING/TO_ASSIGN 可改本人工单 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "WORK_ORDER", targetId = "#id", content = "'居民修改工单：' + #dto.title")
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

    /* 管理端显式补齐已受理/已派单历史工单的群聊；只改会话引用，不推进工单或扩大读取权限。 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "WORK_ORDER", targetId = "#id", content = "'打开或恢复工单沟通群'")
    public WorkOrderVO ensureConversation(Long id) {
        if (!SecurityUtils.hasRole(RoleConstants.ADMIN) && !SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            throw new ForbiddenException("仅管理方可恢复工单沟通群");
        }
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        if (!Set.of(WorkOrderStatus.TO_ASSIGN, WorkOrderStatus.ASSIGNED, WorkOrderStatus.ACCEPTED,
                WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.TO_CONFIRM, WorkOrderStatus.COMPLETED,
                WorkOrderStatus.CLOSED).contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION, "请先受理工单或派单，再进入沟通群");
        }
        Conversation group = ensureCommunicationGroup(order);
        WorkOrderAssignment assignment = assignmentMapper.selectOne(new LambdaQueryWrapper<WorkOrderAssignment>()
                .eq(WorkOrderAssignment::getWorkOrderId, id).orderByDesc(WorkOrderAssignment::getId).last("LIMIT 1"));
        if (assignment != null && group != null && !"CLOSED".equals(group.getStatus())) conversationService.joinWorkOrderGroup(id, assignment.getAssigneeId(), null);
        if (WorkOrderStatus.CLOSED.equals(order.getStatus())) conversationService.closeWorkOrderGroup(id);
        return toVO(order);
    }

    /* 建群以(type,related_id)唯一键幂等；只回写引用，避免恢复沟通时覆盖并发业务状态。 */
    private Conversation ensureCommunicationGroup(WorkOrder order) {
        Conversation group = conversationService.createWorkOrderGroup(order.getId(), order.getCommunityId(),
                order.getResidentId(), SecurityUtils.getUserId(), order.getOrderNo());
        if (group != null && !Objects.equals(order.getConversationId(), group.getId())) {
            workOrderMapper.update(null, new LambdaUpdateWrapper<WorkOrder>()
                    .eq(WorkOrder::getId, order.getId()).set(WorkOrder::getConversationId, group.getId()));
            order.setConversationId(group.getId());
        }
        return group;
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

    /* 工单分页：sort 为空/DEFAULT 保持 id 倒序（既有行为不变）；WAIT_DESC 等待时长降序
       （以提交时间升序近似，等待最久者置前）；PRIORITY 紧急优先 + 提交时间升序。
       assigneeId 与 STAFF 范围均以最新派单归属为准，分页前收敛，历史派单只用于留痕 */
    public PageVO<WorkOrderVO> page(long page, long size, String status, String statuses,
                                    String priority, Long categoryId, String keyword,
                                    LocalDateTime startTime, LocalDateTime endTime,
                                    Long assigneeId, String sort) {
        List<String> statusList = splitStatuses(statuses);
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .eq(StringUtils.hasText(status), WorkOrder::getStatus, status)
                .in(!statusList.isEmpty(), WorkOrder::getStatus, statusList)
                .eq(StringUtils.hasText(priority), WorkOrder::getPriority, priority)
                .eq(categoryId != null, WorkOrder::getCategoryId, categoryId)
                .ge(startTime != null, WorkOrder::getCreatedAt, startTime)
                .le(endTime != null, WorkOrder::getCreatedAt, endTime)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(WorkOrder::getTitle, keyword)
                        .or().like(WorkOrder::getContent, keyword)
                        .or().like(WorkOrder::getOrderNo, keyword));
        if (assigneeId != null) {
            restrictCurrentAssignee(wrapper, assigneeId);
        }
        /* 本人范围与显式处理人筛选取交集，不能通过筛选参数扩大读取权限。 */
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(WorkOrder::getResidentId, SecurityUtils.getUserId());
        } else if (SecurityUtils.hasRole(RoleConstants.STAFF)) {
            restrictCurrentAssignee(wrapper, SecurityUtils.getUserId());
        }
        applySort(wrapper, sort);
        Page<WorkOrder> result = workOrderMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.getRecords() == null ? List.of() : toVOList(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /* 与详情 checkAssignee 的 id 倒序口径一致；EXISTS 在数据库分页与计数之前过滤。
       仅参数绑定人员 ID，不能将历史任一次派单当作当前列表读取授权。 */
    private void restrictCurrentAssignee(LambdaQueryWrapper<WorkOrder> wrapper, Long assigneeId) {
        wrapper.apply("""
                EXISTS (SELECT 1 FROM work_order_assignment current_assignment
                    WHERE current_assignment.work_order_id = work_order.id
                      AND current_assignment.assignee_id = {0}
                      AND current_assignment.id = (
                          SELECT MAX(latest_assignment.id) FROM work_order_assignment latest_assignment
                          WHERE latest_assignment.work_order_id = work_order.id))
                """, assigneeId);
    }

    /* 排序装配：取值由 SORT_* 常量枚举，非枚举值回落默认 id 倒序。
       PRIORITY 的「紧急优先」为 CASE 表达式，Lambda 列名无法表达，走 last 拼装
       （取值来自常量，无注入面） */
    private void applySort(LambdaQueryWrapper<WorkOrder> wrapper, String sort) {
        if (SORT_WAIT_DESC.equalsIgnoreCase(sort)) {
            wrapper.orderByAsc(WorkOrder::getCreatedAt).orderByDesc(WorkOrder::getId);
        } else if (SORT_PRIORITY.equalsIgnoreCase(sort)) {
            wrapper.last("ORDER BY (CASE WHEN priority = '" + WorkOrderPriority.URGENT
                    + "' THEN 0 ELSE 1 END) ASC, created_at ASC, id ASC");
        } else {
            wrapper.orderByDesc(WorkOrder::getId);
        }
    }

    /* 派单：PENDING/TO_ASSIGN → ASSIGNED；ASSIGNED → ASSIGNED 为改派（重新派单覆盖
       当前处理人）；已接单及之后不可改派（架构 §6.1，DEF-012） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'派单给服务人员 ' + #dto.assigneeId")
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
        /* 直接派单也完成管理方受理意愿，不能跳过R74沟通群；改派幂等复用并让派单人入群。 */
        ensureCommunicationGroup(order);
        conversationService.joinWorkOrderGroup(id, dto.getAssigneeId(), assignee.getRealName());
        /* 通知双向触达：服务人员接单提醒（sys_user 身份空间）+ 居民派单进度（resident 身份空间） */
        notificationService.createForUser(dto.getAssigneeId(), order.getCommunityId(), "新工单派发",
                "工单 " + order.getOrderNo() + " 已派单给您，请及时接单",
                "WORK_ORDER", "WORK_ORDER", id);
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单已派单",
                "您的工单 " + order.getOrderNo() + " 已派单给 " + assignee.getRealName(),
                "WORK_ORDER", "WORK_ORDER", id);
        log.info("工单已派单：orderId={}, assigneeId={}, operator={}",
                id, dto.getAssigneeId(), SecurityUtils.getUserId());
    }

    /* 接单：ASSIGNED → ACCEPTED；仅被派单的服务人员 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'服务人员接单'")
    public void accept(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        /* 并发接单幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.ACCEPTED)) {
            return;
        }
        fillAcceptTime(id);
        appendProcess(id, "ACCEPT", WorkOrderStatus.ASSIGNED, WorkOrderStatus.ACCEPTED, remark);
        /* R48/R74 事件 4「接单」：通知服务人员（留痕提醒）+ 该社区绑定管理员；
           §9.4.2.6 正文的「通知居民」按用户裁决 2026-09-23 属**必须收到**项（§9.21.4 事件 4 该格 ✅） */
        notifyCurrentAssignee(order, "工单已接单",
                "工单 " + order.getOrderNo() + " 已接单，请按约上门", id);
        notifyCommunityAdmins(order, "工单已接单",
                "工单 " + order.getOrderNo() + " 已被服务人员接单");
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "服务人员已接单",
                "您的工单 " + order.getOrderNo() + " 已被服务人员接单",
                "WORK_ORDER", "WORK_ORDER", id);
    }

    /* 开始处理：ACCEPTED → IN_PROGRESS */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'开始处理工单'")
    public void process(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        /* 并发开始处理幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.IN_PROGRESS)) {
            return;
        }
        appendProcess(id, "PROCESS", WorkOrderStatus.ACCEPTED, WorkOrderStatus.IN_PROGRESS, remark);
        /* R48/R74 事件 5「开始处理」：收件对象＝居民提交人（**必须收到**，
           用户裁决 2026-09-23：§9.4.2.7 正文写到的收件人即必须送到，§9.21.4 事件 5 该格 ✅） */
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单处理中",
                "您的工单 " + order.getOrderNo() + " 服务人员已开始处理",
                "WORK_ORDER", "WORK_ORDER", id);
    }

    /* 完成处理：IN_PROGRESS → TO_CONFIRM（待居民确认） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'提交处理结果'")
    public void complete(Long id, String solution) {
        WorkOrder order = requireOrder(id);
        checkAssignee(order);
        /* 并发提交结果幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.TO_CONFIRM)) {
            return;
        }
        appendProcess(id, WorkOrderProcessAction.COMPLETE, WorkOrderStatus.IN_PROGRESS, WorkOrderStatus.TO_CONFIRM, solution);
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单已处理完成",
                "您的工单 " + order.getOrderNo() + " 已处理完成，请确认", "WORK_ORDER", "WORK_ORDER", id);
        /* R48/R74 事件 6「提交结果」：另通知该社区绑定管理员 */
        notifyCommunityAdmins(order, "工单待居民确认",
                "工单 " + order.getOrderNo() + " 已提交处理结果，等待居民确认");
    }

    /* 居民确认：TO_CONFIRM → COMPLETED */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'居民确认完成'")
    public void confirm(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        /* 并发居民确认幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.COMPLETED)) {
            return;
        }
        appendProcess(id, "CONFIRM", WorkOrderStatus.TO_CONFIRM, WorkOrderStatus.COMPLETED, remark);
        notifyCurrentAssignee(order, "居民已确认工单",
                "工单 " + order.getOrderNo() + " 已被居民确认完成", id);
        /* R48/R74 事件 7「居民确认」：另通知该社区绑定管理员（等待终审关闭） */
        notifyCommunityAdmins(order, "工单待终审关闭",
                "工单 " + order.getOrderNo() + " 居民已确认完成，等待管理端终审关闭");
    }

    /* 居民不满意退回：TO_CONFIRM → IN_PROGRESS（R22「注明原因」——DTO @NotBlank 保证；
       DEF-019 新增），退回后服务人员重新处理 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'居民不满意退回：' + #reason")
    public void returnBack(Long id, String reason) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        /* 并发退回幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.IN_PROGRESS)) {
            return;
        }
        appendProcess(id, "RETURN", WorkOrderStatus.TO_CONFIRM, WorkOrderStatus.IN_PROGRESS,
                "居民不满意退回：" + reason);
        notifyCurrentAssignee(order, "工单被退回处理中",
                "工单 " + order.getOrderNo() + " 被居民退回：" + reason, id);
    }

    /* 终审关闭（R74 / 接口设计 §9.21.3 复用 §9.4.2.10）：仅 COMPLETED → CLOSED
       （状态机 ALLOWED_TRANSITIONS 已收窄，其余状态一律 5302），
       同一事务触发工单临时群聊**资源回收**（会话 CLOSED + closed_at，只读留痕不物理删除） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'管理员终审关闭工单'")
    public void close(Long id, String remark) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        /* 重复关闭幂等（需求口径「重复关闭不报错」）：已是 CLOSED 终态直接返回成功，
           不重复写处理记录、不重复发通知、不重复关群；其余非法流转（如 PENDING → CLOSED）
           仍由 transition 依状态机拒绝并返回 5302 */
        if (WorkOrderStatus.CLOSED.equals(order.getStatus())) {
            log.info("工单已处于已关闭终态，重复关闭按幂等处理（不重复留痕/通知）：orderId={}", id);
            return;
        }
        /* 并发终审关闭幂等跳过：赢家已流转，不重复留痕/关群/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.CLOSED)) {
            return;
        }
        appendProcess(id, "CLOSE", WorkOrderStatus.COMPLETED, WorkOrderStatus.CLOSED, remark);
        /* 回收前留一条系统留痕（关闭后会话只读，该消息仍在历史可查） */
        Conversation group = conversationService.findWorkOrderGroup(id);
        if (group != null) {
            conversationService.sendSystemMessage(group.getId(), "工单已终审结束（已关闭），本群停止接收新消息，历史记录保留可查");
            conversationService.closeWorkOrderGroup(id);
        }
        /* R48/R74 事件 8「终审关闭」：通知居民提交人 + 该社区绑定管理员 */
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单已结束",
                "您的工单 " + order.getOrderNo() + " 已终审结束（已关闭）", "WORK_ORDER", "WORK_ORDER", id);
        notifyCommunityAdmins(order, "工单已关闭",
                "工单 " + order.getOrderNo() + " 已终审关闭");
        log.info("工单终审关闭：orderId={}, conversationId={}, closed={}", id,
                order.getConversationId(), group != null);
    }

    /* 驳回：PENDING/TO_ASSIGN → REJECTED（终态） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'驳回工单：' + #reason")
    public void reject(Long id, String reason) {
        WorkOrder order = requireOrder(id);
        SecurityUtils.checkCommunityAccess(order.getCommunityId());
        String oldStatus = order.getStatus();
        /* 并发驳回幂等跳过：赢家已流转，不重复留痕/通知（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.REJECTED)) {
            return;
        }
        appendProcess(id, "REJECT", oldStatus, WorkOrderStatus.REJECTED, reason);
        /* R48 工单流转事件全覆盖（DEF-020）：驳回通知提交居民 */
        notificationService.createForResident(order.getResidentId(), order.getCommunityId(), "工单已驳回",
                "您的工单 " + order.getOrderNo() + " 被驳回：" + reason,
                "WORK_ORDER", "WORK_ORDER", id);
    }

    /* 取消：仅 PENDING/TO_ASSIGN 居民可取消（R19/架构 §6.1，流转表拦截其余状态，DEF-012） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "WORK_ORDER", targetId = "#id", content = "'居民取消工单：' + #reason")
    public void cancel(Long id, String reason) {
        WorkOrder order = requireOrder(id);
        checkResidentOwner(order);
        String oldStatus = order.getStatus();
        /* 并发取消幂等跳过：赢家已流转，不重复留痕（OCR DEF-116） */
        if (!transition(order, WorkOrderStatus.CANCELLED)) {
            return;
        }
        appendProcess(id, "CANCEL", oldStatus, WorkOrderStatus.CANCELLED, reason);
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

    /**
     * 可派单服务人员选项（R20 + V19 调度推荐）：启用状态 STAFF 账号，最小暴露面。
     * 推荐档位（越小越推荐）：1-常驻本社区且擅长该类别、2-常驻本社区、
     * 3-擅长该类别、4-其他；同档位按在手工单数升序（负载均衡），再按账号 ID 稳定排序。
     * 社区/类别绑定、社区名称、今日班次、在手工单数各自批量查询，无逐人查库。
     * 范围外 STAFF 仍保留在候选池（R20 判据仅要求启用状态，不做硬过滤）；
     * ADMIN 未显式传社区时以其唯一绑定社区作为匹配社区。
     */
    public List<StaffOptionVO> assignableStaff(Long communityId, Long categoryId) {
        List<SysUser> staffList = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, RoleConstants.STAFF)
                .eq(SysUser::getStatus, CommonStatus.ACTIVE)
                .orderByAsc(SysUser::getId));
        if (staffList.isEmpty()) {
            return List.of();
        }
        Long matchCommunityId = communityId;
        if (matchCommunityId == null && SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            /* 未显式传社区时：ADMIN 取其绑定社区（单绑定直接用，多绑定不预置） */
            var bound = SecurityUtils.getCommunityIds();
            matchCommunityId = bound.size() == 1 ? bound.iterator().next() : null;
        }
        List<Long> staffIds = staffList.stream().map(SysUser::getId).toList();
        Map<Long, List<Long>> communityBindings = staffCapabilityService.communityIdsByStaff(staffIds);
        Map<Long, List<Long>> categoryBindings = staffCapabilityService.categoryIdsByStaff(staffIds);
        Map<Long, String> communityNames = staffCapabilityService.communityNamesByStaff(staffIds);
        Map<Long, String> shiftLabels = staffScheduleService.shiftLabelsOn(
                staffIds, matchCommunityId, LocalDate.now());
        Map<Long, Long> activeOrders = activeOrderCountByAssignee(staffIds);

        List<StaffOptionVO> options = new ArrayList<>(staffList.size());
        for (SysUser staff : staffList) {
            boolean matchedCommunity = matchCommunityId != null
                    && communityBindings.getOrDefault(staff.getId(), List.of()).contains(matchCommunityId);
            boolean matchedCategory = categoryId != null
                    && categoryBindings.getOrDefault(staff.getId(), List.of()).contains(categoryId);
            StaffOptionVO vo = StaffOptionVO.of(staff.getId(), staff.getRealName());
            vo.setRecommendLevel(recommendLevel(matchedCommunity, matchedCategory));
            vo.setMatchedCommunity(matchedCommunity);
            vo.setMatchedCategory(matchedCategory);
            vo.setCommunityNames(communityNames.get(staff.getId()));
            vo.setTodayShiftLabel(shiftLabels.get(staff.getId()));
            vo.setActiveOrderCount(activeOrders.getOrDefault(staff.getId(), 0L).intValue());
            options.add(vo);
        }
        options.sort(Comparator.comparing(StaffOptionVO::getRecommendLevel)
                .thenComparing(StaffOptionVO::getActiveOrderCount)
                .thenComparing(StaffOptionVO::getId));
        return options;
    }

    /* 推荐档位：常驻+擅长 > 常驻 > 擅长 > 其他 */
    private int recommendLevel(boolean matchedCommunity, boolean matchedCategory) {
        if (matchedCommunity && matchedCategory) {
            return 1;
        }
        if (matchedCommunity) {
            return 2;
        }
        return matchedCategory ? 3 : 4;
    }

    public WorkOrder requireOrder(Long id) {
        WorkOrder order = workOrderMapper.selectById(id);
        if (order == null) {
            throw new ResourceNotFoundException("工单不存在");
        }
        return order;
    }

    /* 状态机校验 + 主表条件更新（OCR DEF-116）：UPDATE ... WHERE id=? AND status=<流转前状态>，
       并发同流转只成功一路；0 行时重读——已是目标状态按幂等返回 false（调用方据此跳过
       留痕/建群/通知，对齐「重复关闭幂等」DEF-097 口径），状态漂移抛 5302。
       同态重复动作与非法流转仍由流转表先行拒绝（DEF-011：已接单不可被再接、已完成不可再确认，
       不产生处理记录）。返回 true=本次完成流转，false=并发赢家已完成同目标流转（幂等跳过）。 */
    private boolean transition(WorkOrder order, String target) {
        String current = order.getStatus();
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION,
                    String.format("工单状态不允许从 %s 流转到 %s", current, target));
        }
        order.setStatus(target);
        int rows = workOrderMapper.update(order, new LambdaUpdateWrapper<WorkOrder>()
                .eq(WorkOrder::getId, order.getId())
                .eq(WorkOrder::getStatus, current));
        if (rows == 0) {
            WorkOrder fresh = workOrderMapper.selectById(order.getId());
            if (fresh != null && order.getStatus().equals(fresh.getStatus())) {
                log.info("工单状态已被并发流转到目标状态，按幂等处理（不重复留痕/建群/通知）：orderId={}, {} -> {}",
                        order.getId(), current, target);
                return false;
            }
            throw new BusinessException(ErrorCode.WORK_ORDER_INVALID_TRANSITION,
                    String.format("工单状态不允许从 %s 流转到 %s（并发变更，当前 %s）",
                            current, target, fresh == null ? "未知" : fresh.getStatus()));
        }
        return true;
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

    /* R48/R74 事件 1「提交」（DEF-072 修复口径）：收件人 = **该工单所属社区的绑定启用管理员**
       + **全部启用超管**，身份空间一律 USER（sys_user.id）；**不跨社区投递**，
       也不发居民口径（提交人即居民本人）。同一账号去重，避免绑定+超管双投递。 */
    private void notifySubmit(WorkOrder order) {
        String title = "新工单待受理";
        String content = "工单 " + order.getOrderNo() + "「" + order.getTitle() + "」已提交，请及时受理";
        Set<Long> targets = new HashSet<>(communityAdminIds(order.getCommunityId()));
        targets.addAll(superAdminIds());
        for (Long adminId : targets) {
            notificationService.createForUser(adminId, order.getCommunityId(), title, content,
                    "WORK_ORDER", "WORK_ORDER", order.getId());
        }
        if (targets.isEmpty()) {
            log.warn("工单提交无管理端收件人（社区无绑定管理员且无启用超管）：orderId={}", order.getId());
        }
    }

    /* 事件 2/4/6/7/8 的「相关管理员」收件：该工单所属社区在 sys_admin_community 绑定的启用管理员，
       不跨社区投递（§9.21.4 行内口径） */
    private void notifyCommunityAdmins(WorkOrder order, String title, String content) {
        for (Long adminId : communityAdminIds(order.getCommunityId())) {
            notificationService.createForUser(adminId, order.getCommunityId(), title, content,
                    "WORK_ORDER", "WORK_ORDER", order.getId());
        }
    }

    /* 社区绑定启用管理员（sys_admin_community → sys_user 角色 ADMIN + 启用态） */
    private List<Long> communityAdminIds(Long communityId) {
        if (communityId == null) {
            return List.of();
        }
        List<Long> boundIds = sysAdminCommunityMapper.selectList(
                        new LambdaQueryWrapper<SysAdminCommunity>()
                                .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).distinct().toList();
        if (boundIds.isEmpty()) {
            return List.of();
        }
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, boundIds)
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .eq(SysUser::getStatus, CommonStatus.ACTIVE))
                .stream().map(SysUser::getId).toList();
    }

    /* 启用超管清单（角色 SUPER_ADMIN + 启用态；工单「提交」事件的全局收件人） */
    private List<Long> superAdminIds() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, RoleConstants.SUPER_ADMIN)
                        .eq(SysUser::getStatus, CommonStatus.ACTIVE))
                .stream().map(SysUser::getId).toList();
    }

    /** 通知当前派单处理人（接单/确认等 STAFF 触达场景；无派单记录时静默跳过） */
    private void notifyCurrentAssignee(WorkOrder order, String title, String content, Long orderId) {
        WorkOrderAssignment latest = assignmentMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .eq(WorkOrderAssignment::getWorkOrderId, orderId)
                        .orderByDesc(WorkOrderAssignment::getId)
                        .last("LIMIT 1"));
        if (latest != null) {
            notificationService.createForUser(latest.getAssigneeId(), order.getCommunityId(),
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

    /** 工单号：WO + yyyyMMdd + 4 位随机序号；撞唯一键时循环重试（上限 5 次，
        仍撞抛冲突——4 位随机空间下单日万级提交才可能耗尽，教学规模不可达，
        DEF-026：原实现无重试直接 409） */
    private String generateOrderNo() {
        for (int i = 0; i < ORDER_NO_RETRY_LIMIT; i++) {
            String orderNo = "WO" + LocalDate.now().format(ORDER_NO_DATE)
                    + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
            Long exists = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getOrderNo, orderNo));
            if (exists == 0) {
                return orderNo;
            }
            log.warn("工单号撞号重试（{}/{}）：{}", i + 1, ORDER_NO_RETRY_LIMIT, orderNo);
        }
        throw new BusinessException(ErrorCode.OPERATION_FAILED, "工单号生成失败，请稍后重试");
    }

    /* statuses 多选参数拆分（逗号分隔状态码列表，空白容错；null/空返回空列表） */
    private List<String> splitStatuses(String statuses) {
        if (!StringUtils.hasText(statuses)) {
            return List.of();
        }
        return java.util.Arrays.stream(statuses.split(","))
                .map(String::trim).filter(StringUtils::hasText).toList();
    }

    private WorkOrderVO toVO(WorkOrder order) {
        List<WorkOrderVO> records = toVOList(List.of(order));
        return records.isEmpty() ? WorkOrderVO.from(order) : records.get(0);
    }

    /* 工单视图装配（批量）：居民姓名、类别名称、当前处理人（派单表末条）、处理人姓名、
       处理人今日班次、处理人在手工单数、状态进入时间各一次批量查询，逐单仅内存装配 */
    /** 将有界工单集合批量装配为现有详情，避免逐条详情查询。 */
    public List<WorkOrderVO> currentStaffTodos(Long staffId) {
        return toVOList(workOrderMapper.selectCurrentTodos(staffId));
    }

    private List<WorkOrderVO> toVOList(List<WorkOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, String> residentNames = residentNames(
                orders.stream().map(WorkOrder::getResidentId).distinct().toList());
        Map<Long, String> categoryNames = categoryNames(
                orders.stream().map(WorkOrder::getCategoryId).distinct().toList());
        Map<Long, Long> assignees = latestAssignees(orders.stream().map(WorkOrder::getId).toList());
        List<Long> assigneeIds = assignees.values().stream().distinct().toList();
        Map<Long, String> assigneeNames = sysUserNames(assigneeIds);
        Map<Long, String> shiftLabels = staffScheduleService.shiftLabelsOn(
                assigneeIds, null, LocalDate.now());
        Map<Long, Long> activeOrders = activeOrderCountByAssignee(assigneeIds);
        Map<Long, LocalDateTime> enteredAt = statusEnteredTimes(orders);

        List<WorkOrderVO> records = new ArrayList<>(orders.size());
        for (WorkOrder order : orders) {
            WorkOrderVO vo = WorkOrderVO.from(order);
            vo.setResidentName(residentNames.get(order.getResidentId()));
            vo.setCategoryName(categoryNames.get(order.getCategoryId()));
            Long assigneeId = assignees.get(order.getId());
            if (assigneeId != null) {
                vo.setAssigneeId(assigneeId);
                vo.setAssigneeName(assigneeNames.get(assigneeId));
                vo.setAssigneeShiftLabel(shiftLabels.get(assigneeId));
                vo.setAssigneeActiveOrders(activeOrders.getOrDefault(assigneeId, 0L).intValue());
            }
            fillDispatch(vo, order, enteredAt.get(order.getId()));
            records.add(vo);
        }
        return records;
    }

    private Map<Long, String> residentNames(List<Long> residentIds) {
        if (residentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Resident resident : residentMapper.selectList(new LambdaQueryWrapper<Resident>()
                .in(Resident::getId, residentIds))) {
            names.put(resident.getId(), resident.getRealName());
        }
        return names;
    }

    private Map<Long, String> categoryNames(List<Long> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (ServiceCategory category : categoryMapper.selectList(new LambdaQueryWrapper<ServiceCategory>()
                .in(ServiceCategory::getId, categoryIds))) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    private Map<Long, String> sysUserNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (SysUser user : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, userIds))) {
            names.put(user.getId(), user.getRealName());
        }
        return names;
    }

    /* 各工单当前处理人：一次批量查询派单表，内存中按派单 ID 递增取每单末条（改派覆盖） */
    private Map<Long, Long> latestAssignees(List<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (WorkOrderAssignment assignment : assignmentMapper.selectList(
                new LambdaQueryWrapper<WorkOrderAssignment>()
                        .in(WorkOrderAssignment::getWorkOrderId, orderIds)
                        .orderByAsc(WorkOrderAssignment::getId))) {
            if (assignment.getAssigneeId() != null) {
                result.put(assignment.getWorkOrderId(), assignment.getAssigneeId());
            }
        }
        return result;
    }

    /* 未完结工单按当前处理人计数：先取相关人员派单涉及的工单、再筛未完结、
       最后按每单最新派单记录归属计数（三次批量查询，不逐人查库） */
    private Map<Long, Long> activeOrderCountByAssignee(List<Long> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        List<Long> involvedOrderIds = assignmentMapper.selectList(
                        new LambdaQueryWrapper<WorkOrderAssignment>()
                                .in(WorkOrderAssignment::getAssigneeId, staffIds))
                .stream().map(WorkOrderAssignment::getWorkOrderId).distinct().toList();
        if (involvedOrderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> activeOrderIds = workOrderMapper.selectList(new LambdaQueryWrapper<WorkOrder>()
                        .select(WorkOrder::getId)
                        .in(WorkOrder::getId, involvedOrderIds)
                        .notIn(WorkOrder::getStatus, TERMINAL_STATUSES))
                .stream().map(WorkOrder::getId).toList();
        if (activeOrderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Long assigneeId : latestAssignees(activeOrderIds).values()) {
            if (staffIds.contains(assigneeId)) {
                counts.merge(assigneeId, 1L, Long::sum);
            }
        }
        return counts;
    }

    /* 状态进入时间：处理时间线中「流转到当前状态」的最新记录时间；
       无对应记录时回落提交时间（由调用方 fillDispatch 兜底） */
    private Map<Long, LocalDateTime> statusEnteredTimes(List<WorkOrder> orders) {
        Map<Long, String> statusByOrder = new HashMap<>();
        orders.forEach(order -> statusByOrder.put(order.getId(), order.getStatus()));
        Map<Long, LocalDateTime> enteredAt = new HashMap<>();
        for (WorkOrderProcess process : processMapper.selectList(new LambdaQueryWrapper<WorkOrderProcess>()
                .in(WorkOrderProcess::getWorkOrderId, statusByOrder.keySet())
                .orderByAsc(WorkOrderProcess::getId))) {
            String currentStatus = statusByOrder.get(process.getWorkOrderId());
            if (process.getCreatedAt() == null || process.getNewStatus() == null
                    || !process.getNewStatus().equals(currentStatus)) {
                continue;
            }
            enteredAt.merge(process.getWorkOrderId(), process.getCreatedAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return enteredAt;
    }

    /* 等待时长与调度标记：等待时长以状态进入时间（无则提交时间）计，
       OVERDUE 按当前状态各自阈值判定，URGENT 限未完结的紧急单，NEW 为近 2 小时新建 */
    private void fillDispatch(WorkOrderVO vo, WorkOrder order, LocalDateTime enteredAt) {
        LocalDateTime base = enteredAt != null ? enteredAt : order.getCreatedAt();
        Long waitedMinutes = base == null ? null
                : Duration.between(base, LocalDateTime.now()).toMinutes();
        vo.setWaitedMinutes(waitedMinutes);
        vo.setDispatchFlag(resolveDispatchFlag(order, waitedMinutes));
    }

    private String resolveDispatchFlag(WorkOrder order, Long waitedMinutes) {
        if (waitedMinutes != null && isOverdue(order.getStatus(), waitedMinutes)) {
            return DispatchFlag.OVERDUE;
        }
        if (WorkOrderPriority.URGENT.equals(order.getPriority())
                && !TERMINAL_STATUSES.contains(order.getStatus())) {
            return DispatchFlag.URGENT;
        }
        if (order.getCreatedAt() != null && Duration.between(order.getCreatedAt(),
                LocalDateTime.now()).toMinutes() <= NEW_ORDER_MINUTES) {
            return DispatchFlag.NEW;
        }
        return DispatchFlag.NORMAL;
    }

    private boolean isOverdue(String status, long waitedMinutes) {
        return switch (status == null ? "" : status) {
            case WorkOrderStatus.PENDING, WorkOrderStatus.TO_ASSIGN -> waitedMinutes > OVERDUE_ACCEPT_MINUTES;
            case WorkOrderStatus.ASSIGNED -> waitedMinutes > OVERDUE_ASSIGN_MINUTES;
            case WorkOrderStatus.IN_PROGRESS -> waitedMinutes > OVERDUE_PROCESS_MINUTES;
            default -> false;
        };
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
