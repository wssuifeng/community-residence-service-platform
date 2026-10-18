package com.community.residence.common.workorder.service;

import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.workorder.constant.WorkOrderStatus;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.upload.service.FileUploadService;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.common.workorder.dto.CreateWorkOrderDTO;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.entity.WorkOrder;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderAttachmentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.mapper.WorkOrderProcessMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工单通知事件集**收件对象矩阵锁死用例**（R48/R74，防文档-实现漂移）。
 *
 * <p><b>矩阵权威出处：`docs/30_系统设计/接口设计.md` §9.21.4「通知事件集」
 * （v1.6 / 2026-09-22 / R74 批次）收件对象矩阵 8 事件 × 4 列
 * （居民提交人 / 服务人员 / 相关管理员 / 超管）：</b>
 *
 * <pre>
 * #  事件      居民提交人  服务人员  相关管理员  超管   备注
 * 1  提交        —        —        ✅       ✅   DEF-072 修复口径
 * 2  受理        ✅        —        ✅       —    （受理管理员本人另留操作痕迹）
 * 3  派单        ✅        ✅        —       —    服务人员同时入群
 * 4  接单        ✅        ✅        ✅       —    §9.4.2.6 正文「通知居民」⇒ 必须收到
 * 5  开始处理     ✅        —        —       —    §9.4.2.7 正文「通知居民」⇒ 必须收到
 * 6  提交结果     ✅        —        ✅       —    状态流转至 TO_CONFIRM
 * 7  居民确认     —        ✅        ✅       —    已处理服务人员（§9.4.2.9 正文）⇒ 必须收到
 * 8  终审关闭     ✅        —        ✅       —    联动会话资源回收
 * </pre>
 *
 * <p>§9.21.4 行内口径（<b>用户裁决 2026-09-23 更新</b>）：「上表为每事件<b>必须收到</b>的收件对象；
 * <b>凡设计中写到的收件人一律按「必须收到」对待</b>，不存在「可以不送」的提醒项」
 * ——原「必须触达 / 额外提醒（非必须）」双档表述已作废，上表 4/5/7 三行原标 — 的格子
 * 已按该裁决逐格改为 ✅（<b>仅措辞与矩阵标注变化，实现本就全部发送，代码未改</b>）。
 *
 * <p><b>本用例的定位</b>：DEF-101 / DEF-102 经查证为 **50 阶段测试文档手抄错误**
 * （`13_核心业务流程三端联动手测用例.md` 原矩阵 4/5/7/8 四行与设计源头不符），
 * 实现经核验**符合**设计源头 §9.21.4。<b>按 AGENTS.md §二「30 阶段设计为权威」，
 * 以设计源头为准修订下游文档，未改实现</b>；本用例把该矩阵锁死，防止再次漂移。
 *
 * <p>另含 **DEF-067 回归点**：`resident.id` 与 `sys_user.id` 撞号（本用例中双方均为 1L）时，
 * 两个身份空间的通知**互不串号**——居民口径只走 {@code createForResident}（RESIDENT），
 * 管理端/服务人员口径只走 {@code createForUser}（USER）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("工单通知矩阵锁死（§9.21.4 8 事件 × 4 列）")
class WorkOrderNotificationMatrixTest {

    private static final Long ORDER_ID = 1L;
    private static final Long COMMUNITY_ID = 1L;

    /* ★ DEF-067 撞号回归：居民提交人与超管故意取同一个数值 1 */
    private static final Long RESIDENT_ID = 1L;
    private static final Long SUPER_ADMIN_ID = 1L;

    private static final Long BOUND_ADMIN_ID = 3L;
    private static final Long STAFF_ID = 11L;
    private static final Long OPERATOR_ADMIN_ID = 9L;
    private static final Long GROUP_ID = 77L;
    private static final String ORDER_NO = "WO202609220001";

    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private WorkOrderProcessMapper processMapper;
    @Mock
    private WorkOrderAssignmentMapper assignmentMapper;
    @Mock
    private WorkOrderAttachmentMapper attachmentMapper;
    @Mock
    private ServiceCategoryMapper categoryMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private FileUploadService fileUploadService;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private StaffCapabilityService staffCapabilityService;
    @Mock
    private StaffScheduleService staffScheduleService;
    @Mock
    private ConversationService conversationService;

    @InjectMocks
    private WorkOrderService service;

    private WorkOrder order;

    @BeforeEach
    void setUp() {
        order = new WorkOrder();
        order.setId(ORDER_ID);
        order.setOrderNo(ORDER_NO);
        order.setResidentId(RESIDENT_ID);
        order.setCommunityId(COMMUNITY_ID);
        order.setCategoryId(1L);
        order.setTitle("厨房漏水");
        order.setStatus(WorkOrderStatus.PENDING);
        lenient().when(workOrderMapper.selectById(ORDER_ID)).thenReturn(order);
        lenient().when(workOrderMapper.update(any(WorkOrder.class), any())).thenReturn(1);
        lenient().when(workOrderMapper.insert(ArgumentMatchers.<WorkOrder>any())).thenAnswer(invocation -> {
            WorkOrder inserted = invocation.getArgument(0);
            inserted.setId(ORDER_ID);
            return 1;
        });

        Conversation group = new Conversation();
        group.setId(GROUP_ID);
        group.setType(ConversationType.WORK_ORDER_GROUP);
        group.setStatus(ConversationStatus.ACTIVE);
        group.setRelatedId(ORDER_ID);
        lenient().when(conversationService.createWorkOrderGroup(anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn(group);
        lenient().when(conversationService.findWorkOrderGroup(ORDER_ID)).thenReturn(group);

        /* 该社区绑定启用管理员（「相关管理员」＝ sys_admin_community 绑定的启用 ADMIN） */
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setCommunityId(COMMUNITY_ID);
        binding.setAdminId(BOUND_ADMIN_ID);
        lenient().when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        SysUser boundAdmin = sysUser(BOUND_ADMIN_ID, "ADMIN");
        SysUser superAdmin = sysUser(SUPER_ADMIN_ID, "SUPER_ADMIN");
        /* 调用序：社区绑定管理员查询 → 超管查询（仅事件 1 走第二步） */
        lenient().when(sysUserMapper.selectList(any()))
                .thenReturn(List.of(boundAdmin), List.of(superAdmin));
    }

    private SysUser sysUser(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus("ACTIVE");
        user.setRealName(role + id);
        return user;
    }

    private MockedStatic<SecurityUtils> login(Long userId, boolean isResident) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole("RESIDENT")).thenReturn(isResident);
        return mocked;
    }

    /* 派单记录：checkAssignee / notifyCurrentAssignee 取最新一条 */
    private void stubLatestAssignment() {
        WorkOrderAssignment assignment = new WorkOrderAssignment();
        assignment.setWorkOrderId(ORDER_ID);
        assignment.setAssigneeId(STAFF_ID);
        lenient().when(assignmentMapper.selectOne(any())).thenReturn(assignment);
    }

    /** 抓取 USER 身份空间实收件人集合（DEF-067：必须按 owner_type 区分，不能只数 user_id） */
    private Set<Long> userRecipients() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, org.mockito.Mockito.atLeast(0))
                .createForUser(captor.capture(), anyLong(), anyString(), anyString(),
                        anyString(), anyString(), anyLong());
        return new LinkedHashSet<>(captor.getAllValues());
    }

    /** 抓取 RESIDENT 身份空间实收件人集合 */
    private Set<Long> residentRecipients() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, org.mockito.Mockito.atLeast(0))
                .createForResident(captor.capture(), anyLong(), anyString(), anyString(),
                        anyString(), anyString(), anyLong());
        return new LinkedHashSet<>(captor.getAllValues());
    }

    /* ── 事件 1 提交 → 相关管理员 ✅ + 超管 ✅；居民 —、服务人员 — ───────────── */

    @Test
    @DisplayName("事件1 提交：必须收到 = 该社区绑定管理员 + 超管；居民/服务人员不收")
    void event1_submit() {
        ServiceCategory category = new ServiceCategory();
        category.setId(1L);
        category.setCommunityId(COMMUNITY_ID);
        category.setName("水电维修");
        category.setIsActive(1);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);

        CreateWorkOrderDTO dto = new CreateWorkOrderDTO();
        dto.setCategoryId(1L);
        dto.setTitle("厨房漏水");
        dto.setContent("水槽下方渗水");
        dto.setContactPhone("13905710101");

        try (MockedStatic<SecurityUtils> mocked = login(RESIDENT_ID, true)) {
            service.create(dto);
        }

        assertThat(userRecipients()).containsExactlyInAnyOrder(BOUND_ADMIN_ID, SUPER_ADMIN_ID);
        assertThat(residentRecipients()).as("事件1 不投居民身份空间").isEmpty();
    }

    /* ── 事件 2 受理 → 居民提交人 ✅ + 相关管理员 ✅ ───────────────────────── */

    @Test
    @DisplayName("事件2 受理：必须收到 = 居民提交人 + 相关管理员")
    void event2_intake() {
        try (MockedStatic<SecurityUtils> mocked = login(OPERATOR_ADMIN_ID, false)) {
            service.intake(ORDER_ID, "已受理");
        }
        assertThat(userRecipients()).containsExactly(BOUND_ADMIN_ID);
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
    }

    /* ── 事件 3 派单 → 居民提交人 ✅ + 服务人员 ✅ ─────────────────────────── */

    @Test
    @DisplayName("事件3 派单：必须收到 = 被派服务人员 + 居民提交人（管理员不投）")
    void event3_assign() {
        order.setStatus(WorkOrderStatus.TO_ASSIGN);
        when(sysUserMapper.selectById(STAFF_ID)).thenReturn(sysUser(STAFF_ID, "STAFF"));
        AssignWorkOrderDTO dto = new AssignWorkOrderDTO();
        dto.setAssigneeId(STAFF_ID);

        try (MockedStatic<SecurityUtils> mocked = login(OPERATOR_ADMIN_ID, false)) {
            service.assign(ORDER_ID, dto);
        }
        assertThat(userRecipients()).containsExactly(STAFF_ID);
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
    }

    /* ── 事件 4 接单 → 服务人员 ✅ + 相关管理员 ✅ + 居民提交人 ✅ ──────────── */

    @Test
    @DisplayName("事件4 接单：必须收到 = 被派服务人员 + 相关管理员 + 居民提交人")
    void event4_accept() {
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.ASSIGNED);
        try (MockedStatic<SecurityUtils> mocked = login(STAFF_ID, false)) {
            service.accept(ORDER_ID, "已接单");
        }
        assertThat(userRecipients()).containsExactlyInAnyOrder(STAFF_ID, BOUND_ADMIN_ID);
        /* §9.4.2.6 正文「通知居民」⇒ 按用户裁决 2026-09-23 属**必须收到**项 */
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
    }

    /* ── 事件 5 开始处理 → 居民提交人 ✅；服务人员/管理员/超管 — ────────────── */

    @Test
    @DisplayName("事件5 开始处理：必须收到 = 居民提交人；管理员/服务人员/超管均不投")
    void event5_process() {
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.ACCEPTED);
        try (MockedStatic<SecurityUtils> mocked = login(STAFF_ID, false)) {
            service.process(ORDER_ID, "已到现场");
        }
        assertThat(userRecipients())
                .as("§9.21.4 事件 5 四列全—：不得向任何 USER 身份空间投递（DEF-102 定性命中处）")
                .isEmpty();
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
        verify(notificationService, never()).createForUser(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyLong());
    }

    /* ── 事件 6 提交结果 → 居民提交人 ✅ + 相关管理员 ✅ ───────────────────── */

    @Test
    @DisplayName("事件6 提交结果：必须收到 = 居民提交人 + 相关管理员")
    void event6_complete() {
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.IN_PROGRESS);
        try (MockedStatic<SecurityUtils> mocked = login(STAFF_ID, false)) {
            service.complete(ORDER_ID, "已更换密封圈");
        }
        assertThat(userRecipients()).containsExactly(BOUND_ADMIN_ID);
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
    }

    /* ── 事件 7 居民确认 → 相关管理员 ✅ + 已处理服务人员 ✅；居民不投 ──────── */

    @Test
    @DisplayName("事件7 居民确认：必须收到 = 相关管理员 + 已处理服务人员；居民不投")
    void event7_confirm() {
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.TO_CONFIRM);
        try (MockedStatic<SecurityUtils> mocked = login(RESIDENT_ID, true)) {
            service.confirm(ORDER_ID, "确认完成");
        }
        assertThat(userRecipients()).containsExactlyInAnyOrder(STAFF_ID, BOUND_ADMIN_ID);
        assertThat(residentRecipients()).as("事件7 不投居民身份空间").isEmpty();
    }

    /* ── 事件 8 终审关闭 → 居民提交人 ✅ + 相关管理员 ✅；服务人员 — ────────── */

    @Test
    @DisplayName("事件8 终审关闭：必须收到 = 居民提交人 + 相关管理员；被派服务人员不投（DEF-101 定性命中处）")
    void event8_close() {
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.COMPLETED);
        try (MockedStatic<SecurityUtils> mocked = login(OPERATOR_ADMIN_ID, false)) {
            service.close(ORDER_ID, "终审通过");
        }
        assertThat(userRecipients())
                .as("§9.21.4 事件 8 = 相关管理员 ✅、服务人员 —：只投绑定管理员")
                .containsExactly(BOUND_ADMIN_ID);
        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
        /* 服务人员侧不投（矩阵该格为 —；原 13 号矩阵误抄为「居民+服务人员」） */
        assertThat(userRecipients()).doesNotContain(STAFF_ID);
    }

    /* ── 身份空间隔离（DEF-067 回归）────────────────────────────────────── */

    @Test
    @DisplayName("DEF-067 撞号回归：resident.id=1 与 sys_user.id=1 同值，两身份空间通知互不串号")
    void event_isolation_sameIdNoCrosstalk() {
        /* 事件 8：居民提交人 1 与超管 1 同值。
           居民口径只走 createForResident(1)（RESIDENT），管理端口径只走 createForUser(3)（USER）；
           超管 1 不在事件 8 收件人内 ⇒ createForUser(1L) 不得出现。 */
        stubLatestAssignment();
        order.setStatus(WorkOrderStatus.COMPLETED);
        try (MockedStatic<SecurityUtils> mocked = login(OPERATOR_ADMIN_ID, false)) {
            service.close(ORDER_ID, "终审通过");
        }

        assertThat(residentRecipients()).containsExactly(RESIDENT_ID);
        assertThat(userRecipients()).containsExactly(BOUND_ADMIN_ID);
        /* 关键断言：同值 1L 不得因撞号被投进 USER 身份空间 */
        assertThat(userRecipients()).doesNotContain(RESIDENT_ID);
        /* 反向：管理端收件人 3/11 不得被投进 RESIDENT 身份空间 */
        assertThat(residentRecipients()).doesNotContain(BOUND_ADMIN_ID);

        /* 两个入口被分别调用，且从不混用（createForResident 不带 USER、反之亦然） */
        verify(notificationService).createForResident(eq(RESIDENT_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
        verify(notificationService).createForUser(eq(BOUND_ADMIN_ID), eq(COMMUNITY_ID), anyString(), anyString(),
                eq("WORK_ORDER"), eq("WORK_ORDER"), eq(ORDER_ID));
    }

    @Test
    @DisplayName("DEF-067 撞号回归：事件 1 提交（超管 1 与居民 1 同值）只投 USER 空间，不串到 RESIDENT")
    void event1_isolation_superAdminAndResidentSameId() {
        ServiceCategory category = new ServiceCategory();
        category.setId(1L);
        category.setCommunityId(COMMUNITY_ID);
        category.setName("水电维修");
        category.setIsActive(1);
        when(categoryMapper.selectById(1L)).thenReturn(category);
        when(residenceRelationMapper.selectCount(any())).thenReturn(1L);

        CreateWorkOrderDTO dto = new CreateWorkOrderDTO();
        dto.setCategoryId(1L);
        dto.setTitle("厨房漏水");
        dto.setContent("水槽下方渗水");
        dto.setContactPhone("13905710101");

        try (MockedStatic<SecurityUtils> mocked = login(RESIDENT_ID, true)) {
            service.create(dto);
        }
        /* 超管 id=1 与居民 id=1 同值：超管通知必须落在 USER 空间 */
        assertThat(userRecipients()).containsExactlyInAnyOrder(BOUND_ADMIN_ID, SUPER_ADMIN_ID);
        /* 且不得因撞号把该事件串进 RESIDENT 空间（提交事件不发居民） */
        assertThat(residentRecipients()).isEmpty();
    }
}
