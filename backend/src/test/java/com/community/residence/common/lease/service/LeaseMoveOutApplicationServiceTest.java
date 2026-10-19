package com.community.residence.common.lease.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.lease.dto.ApproveMoveOutDTO;
import com.community.residence.common.lease.dto.CreateMoveOutApplicationDTO;
import com.community.residence.common.lease.dto.RejectMoveOutDTO;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.vo.LeaseMoveOutApplicationVO;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 居民自助退租申请回归（R70）：仅本人已生效租约、同租约待审唯一（5603，锁内检查）、
 * 退租日期须落在租期窗口（DEF-130）；通过为跨域联动（租约→已搬出 + 居住关系迁出 +
 * 房屋空置 + 房源状态回收 + 变更留痕 + 关闭租约群聊 + 通知居民），驳回理由必填且
 * 租约保持已生效；通知深链 sourceId 一律为租约 ID（DEF-105）；并发审核以
 * 条件更新抢占终态，失败方幂等返回不重复副作用（DEF-104）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("居民自助退租申请（R70）")
class LeaseMoveOutApplicationServiceTest {
    @org.mockito.Mock private com.community.residence.common.resident.service.ResidenceDependencyService residenceDependencyService;

    private static final Long LEASE_ID = 12L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long APPLICATION_ID = 30L;
    private static final Long ADMIN_ID = 2L;

    @Mock
    private LeaseMoveOutApplicationMapper moveOutMapper;
    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private LeaseMoveOutApplicationService service;

    private LeaseRecord activeLease;
    private LeaseMoveOutApplication pendingApplication;
    private House house;

    /* LambdaUpdateWrapper.set 构造时即解析列名，需实体已注册 TableInfo
       （DEF-104 条件更新引入；本类自我完备，不依赖测试类执行顺序） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant,
                com.community.residence.common.lease.entity.LeaseMoveOutApplication.class);
        TableInfoHelper.initTableInfo(assistant, com.community.residence.common.housing.entity.Housing.class);
    }

    @BeforeEach
    void setUp() {
        activeLease = new LeaseRecord();
        activeLease.setId(LEASE_ID);
        activeLease.setTenantId(RESIDENT_ID);
        activeLease.setCommunityId(3L);
        activeLease.setHouseId(5L);
        activeLease.setStatus("ACTIVE");
        activeLease.setStartDate(LocalDate.now().minusMonths(6));
        activeLease.setEndDate(LocalDate.now().plusMonths(6));

        pendingApplication = new LeaseMoveOutApplication();
        pendingApplication.setId(APPLICATION_ID);
        pendingApplication.setLeaseId(LEASE_ID);
        pendingApplication.setResidentId(RESIDENT_ID);
        pendingApplication.setCommunityId(3L);
        pendingApplication.setHouseId(5L);
        pendingApplication.setMoveOutDate(LocalDate.now().plusDays(15));
        pendingApplication.setStatus("PENDING");

        house = new House();
        house.setId(5L);
        house.setCommunityId(3L);
        house.setStatus("OCCUPIED");

        when(leaseMapper.selectById(LEASE_ID)).thenReturn(activeLease);
        when(leaseMapper.selectByIdForUpdate(LEASE_ID)).thenReturn(activeLease);
        when(moveOutMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication);
        when(moveOutMapper.selectByIdForUpdate(APPLICATION_ID)).thenReturn(pendingApplication);
        when(houseMapper.selectById(5L)).thenReturn(house);
        Community community = new Community();
        community.setId(3L);
        community.setName("演示社区");
        when(communityMapper.selectById(3L)).thenReturn(community);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of());
        when(housingMapper.update(any(), any())).thenReturn(1);
        /* DEF-104：create 的 Redisson 锁与锁内事务模板 */
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        /* DEF-104：approve/reject 的终态条件更新默认放行（0 行路径在具体用例中覆盖） */
        when(moveOutMapper.update(any(), any())).thenReturn(1);
        /* DEF-127：toVO 批量装配上下文的六类批量装载 */
        when(residentMapper.selectBatchIds(any())).thenReturn(List.of());
        when(communityMapper.selectBatchIds(any())).thenReturn(List.of());
        when(houseMapper.selectBatchIds(any())).thenReturn(List.of());
        when(unitMapper.selectBatchIds(any())).thenReturn(List.of());
        when(buildingMapper.selectBatchIds(any())).thenReturn(List.of());
        when(sysUserMapper.selectBatchIds(any())).thenReturn(List.of());
    }

    /* ---------- 创建 ---------- */

    @Test
    @DisplayName("创建：本人 ACTIVE 租约成功落 PENDING")
    void create_ownActiveLease_pending() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(moveOutMapper.selectCount(any())).thenReturn(0L);

            CreateMoveOutApplicationDTO dto = new CreateMoveOutApplicationDTO();
            dto.setMoveOutDate(LocalDate.now().plusDays(10));
            dto.setReason("工作调动");

            LeaseMoveOutApplicationVO vo = service.create(LEASE_ID, dto);

            assertThat(vo.getStatus()).isEqualTo("PENDING");
            assertThat(vo.getMoveOutDate()).isEqualTo(LocalDate.now().plusDays(10));
            ArgumentCaptor<LeaseMoveOutApplication> captor =
                    ArgumentCaptor.forClass(LeaseMoveOutApplication.class);
            verify(moveOutMapper).insert(captor.capture());
            assertThat(captor.getValue().getCommunityId()).isEqualTo(3L);
            assertThat(captor.getValue().getHouseId()).isEqualTo(5L);
        }
    }

    @Test
    @DisplayName("创建：已有待审申请拒绝 MOVE_OUT_APPLICATION_PENDING(5603)")
    void create_pendingExists_rejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(moveOutMapper.selectCount(any())).thenReturn(1L);

            CreateMoveOutApplicationDTO dto = new CreateMoveOutApplicationDTO();
            dto.setMoveOutDate(LocalDate.now().plusDays(10));

            assertThatThrownBy(() -> service.create(LEASE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.MOVE_OUT_APPLICATION_PENDING));
            verify(moveOutMapper, never()).insert(any(LeaseMoveOutApplication.class));
        }
    }

    @Test
    @DisplayName("创建：非本人租约 403")
    void create_othersLease_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(999L);

            CreateMoveOutApplicationDTO dto = new CreateMoveOutApplicationDTO();
            dto.setMoveOutDate(LocalDate.now().plusDays(10));

            assertThatThrownBy(() -> service.create(LEASE_ID, dto))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("创建：非 ACTIVE 租约拒绝 LEASE_STATUS_INVALID(5602)")
    void create_nonActiveLease_rejected() {
        activeLease.setStatus("TO_SIGN");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);

            CreateMoveOutApplicationDTO dto = new CreateMoveOutApplicationDTO();
            dto.setMoveOutDate(LocalDate.now().plusDays(10));

            assertThatThrownBy(() -> service.create(LEASE_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.LEASE_STATUS_INVALID));
        }
    }

    @Test
    @DisplayName("创建：通知深链 sourceId 为租约ID而非申请ID（DEF-105）")
    void create_notifiesAdminWithLeaseIdSourceId() {
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(7L);
        binding.setCommunityId(3L);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            when(moveOutMapper.selectCount(any())).thenReturn(0L);

            CreateMoveOutApplicationDTO dto = new CreateMoveOutApplicationDTO();
            dto.setMoveOutDate(LocalDate.now().plusDays(10));

            service.create(LEASE_ID, dto);

            /* type=LEASE 的深链契约：sourceId 必须是租约 ID（前端跳 /admin/leases/{id}） */
            verify(notificationService).createForUser(eq(7L), eq(3L), eq("居民提交退租申请"),
                    anyString(), eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("创建：退租日期早于起租日/晚于止租日拒绝 INVALID_PARAM(400)（DEF-130）")
    void create_moveOutDateOutsideLeaseTerm_rejected() {
        activeLease.setStartDate(LocalDate.of(2026, 1, 1));
        activeLease.setEndDate(LocalDate.of(2026, 12, 31));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);

            CreateMoveOutApplicationDTO early = new CreateMoveOutApplicationDTO();
            early.setMoveOutDate(LocalDate.of(2025, 12, 31));
            assertThatThrownBy(() -> service.create(LEASE_ID, early))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));

            CreateMoveOutApplicationDTO late = new CreateMoveOutApplicationDTO();
            late.setMoveOutDate(LocalDate.of(2027, 1, 1));
            assertThatThrownBy(() -> service.create(LEASE_ID, late))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM));
            verify(moveOutMapper, never()).insert(any(LeaseMoveOutApplication.class));
        }
    }

    /* ---------- 审核通过 ---------- */

    @Test
    @DisplayName("通过：租约→MOVED_OUT + 居住关系迁出 + 房屋空置 + 状态留痕 + 关群 + 通知居民")
    void approve_fullCascade() {
        ResidenceRelation relation = new ResidenceRelation();
        relation.setId(41L);
        relation.setResidentId(RESIDENT_ID);
        relation.setHouseId(5L);
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            ApproveMoveOutDTO dto = new ApproveMoveOutDTO();
            dto.setHousingStatus("OFFLINE");

            LeaseMoveOutApplicationVO vo = service.approve(APPLICATION_ID, dto);

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            assertThat(activeLease.getStatus()).isEqualTo("MOVED_OUT");
            assertThat(relation.getMoveOutDate()).isEqualTo(pendingApplication.getMoveOutDate());
            assertThat(house.getStatus()).isEqualTo("VACANT");
            verify(changeLogService).logStatus(eq(LEASE_ID), eq("ACTIVE"), eq("MOVED_OUT"), anyString());
            verify(conversationService).closeLeaseGroup(LEASE_ID);
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(3L), eq("退租申请已通过"),
                    anyString(), eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(LEASE_ID));
            verify(housingMapper).update(any(), any());
        }
    }

    @Test
    @DisplayName("通过：已通过的申请幂等返回，不重复流转")
    void approve_alreadyApproved_idempotent() {
        pendingApplication.setStatus("APPROVED");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            service.approve(APPLICATION_ID, new ApproveMoveOutDTO());

            assertThat(activeLease.getStatus()).isEqualTo("ACTIVE");
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(conversationService, never()).closeLeaseGroup(anyLong());
        }
    }

    @Test
    @DisplayName("通过：租约已非 ACTIVE 时拒绝 LEASE_STATUS_INVALID(5602)")
    void approve_leaseNotActive_rejected() {
        activeLease.setStatus("MOVED_OUT");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            assertThatThrownBy(() -> service.approve(APPLICATION_ID, new ApproveMoveOutDTO()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.LEASE_STATUS_INVALID));
        }
    }

    @Test
    @DisplayName("通过：并发赢家已处理（条件更新 0 行 + 已 APPROVED）幂等返回，不重复副作用（DEF-104）")
    void approve_concurrentWinner_idempotent() {
        LeaseMoveOutApplication approved = approvedApplication();
        when(moveOutMapper.update(any(), any())).thenReturn(0);
        when(moveOutMapper.selectById(APPLICATION_ID)).thenReturn(pendingApplication);
        when(moveOutMapper.selectByIdForUpdate(APPLICATION_ID)).thenReturn(pendingApplication, approved);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            LeaseMoveOutApplicationVO vo = service.approve(APPLICATION_ID, new ApproveMoveOutDTO());

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(conversationService, never()).closeLeaseGroup(anyLong());
            verify(notificationService, never()).createForResident(anyLong(), anyLong(), anyString(),
                    anyString(), anyString(), anyString(), anyLong());
        }
    }

    /* ---------- 驳回 ---------- */

    @Test
    @DisplayName("驳回：理由落库、租约保持 ACTIVE、通知居民")
    void reject_keepsLeaseActive() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            RejectMoveOutDTO dto = new RejectMoveOutDTO();
            dto.setReason("押金结算未完成");

            LeaseMoveOutApplicationVO vo = service.reject(APPLICATION_ID, dto);

            assertThat(vo.getStatus()).isEqualTo("REJECTED");
            assertThat(vo.getRejectReason()).isEqualTo("押金结算未完成");
            assertThat(activeLease.getStatus()).isEqualTo("ACTIVE");
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(3L), eq("退租申请未通过"),
                    anyString(), eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(LEASE_ID));
        }
    }

    @Test
    @DisplayName("驳回：仅待审核可驳回，已通过拒绝")
    void reject_approvedApplication_rejected() {
        pendingApplication.setStatus("APPROVED");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            RejectMoveOutDTO dto = new RejectMoveOutDTO();
            dto.setReason("已通过");

            assertThatThrownBy(() -> service.reject(APPLICATION_ID, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.STATE_TRANSITION_INVALID));
        }
    }

    @Test
    @DisplayName("通过/驳回通知深链 sourceId 均为租约ID（DEF-105）")
    void approveAndReject_notifyWithLeaseIdSourceId() {
        when(relationMapper.selectList(any())).thenReturn(List.of());
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);

            service.approve(APPLICATION_ID, new ApproveMoveOutDTO());
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(3L), eq("退租申请已通过"),
                    anyString(), eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(LEASE_ID));

            pendingApplication.setStatus("PENDING");
            RejectMoveOutDTO dto = new RejectMoveOutDTO();
            dto.setReason("押金结算未完成");
            service.reject(APPLICATION_ID, dto);
            verify(notificationService).createForResident(eq(RESIDENT_ID), eq(3L), eq("退租申请未通过"),
                    anyString(), eq("LEASE"), eq("LEASE_MOVE_OUT"), eq(LEASE_ID));
        }
    }

    /* ---------- 共享夹具 ---------- */

    private LeaseMoveOutApplication approvedApplication() {
        LeaseMoveOutApplication approved = new LeaseMoveOutApplication();
        approved.setId(APPLICATION_ID);
        approved.setLeaseId(LEASE_ID);
        approved.setResidentId(RESIDENT_ID);
        approved.setCommunityId(3L);
        approved.setHouseId(5L);
        approved.setMoveOutDate(LocalDate.now().plusDays(15));
        approved.setStatus("APPROVED");
        approved.setReviewerId(2L);
        return approved;
    }
    @Test
    @DisplayName("DEF-127：分页多行仅批量装载六类关联各一次")
    void pageLoadsEachAssociationOnce() {
        LeaseMoveOutApplication second = new LeaseMoveOutApplication();
        second.setId(APPLICATION_ID + 1);
        second.setResidentId(RESIDENT_ID);
        second.setCommunityId(3L);
        second.setHouseId(5L);
        pendingApplication.setReviewerId(9L);
        second.setReviewerId(9L);
        house.setUnitId(7L);
        com.community.residence.common.community.entity.Unit unit =
                new com.community.residence.common.community.entity.Unit();
        unit.setId(7L);
        unit.setBuildingId(8L);
        com.community.residence.common.community.entity.Building building =
                new com.community.residence.common.community.entity.Building();
        building.setId(8L);
        when(houseMapper.selectBatchIds(any())).thenReturn(List.of(house));
        when(unitMapper.selectBatchIds(any())).thenReturn(List.of(unit));
        when(buildingMapper.selectBatchIds(any())).thenReturn(List.of(building));
        when(moveOutMapper.selectPage(any(), any())).thenAnswer(call -> {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<LeaseMoveOutApplication> page =
                    call.getArgument(0);
            page.setRecords(List.of(pendingApplication, second));
            page.setTotal(2);
            return page;
        });
        assertThat(service.page(1, 20, null, null).getRecords()).hasSize(2);
        verify(residentMapper).selectBatchIds(any());
        verify(communityMapper).selectBatchIds(any());
        verify(houseMapper).selectBatchIds(any());
        verify(unitMapper).selectBatchIds(any());
        verify(buildingMapper).selectBatchIds(any());
        verify(sysUserMapper).selectBatchIds(any());
        verify(residentMapper, never()).selectById(any());
        verify(communityMapper, never()).selectById(any());
        verify(houseMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("DEF-104：等待房屋锁后读到已通过申请，租约已搬出仍幂等返回")
    void approveWinnerCommittedBeforeLeaseLock() {
        activeLease.setStatus("MOVED_OUT");
        when(moveOutMapper.selectByIdForUpdate(APPLICATION_ID)).thenReturn(approvedApplication());
        LeaseMoveOutApplicationVO result = service.approve(APPLICATION_ID, new ApproveMoveOutDTO());
        assertThat(result.getStatus()).isEqualTo("APPROVED");
        verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
        verify(conversationService, never()).closeLeaseGroup(anyLong());
    }


    @Test
    @DisplayName("DEF-104：并发驳回等待后读取赢家，不重复通知")
    void rejectConcurrentWinnerIsIdempotent() {
        LeaseMoveOutApplication winner = approvedApplication();
        winner.setStatus("REJECTED");
        winner.setRejectReason("原审核原因");
        when(moveOutMapper.update(any(), any())).thenReturn(0);
        when(moveOutMapper.selectByIdForUpdate(APPLICATION_ID)).thenReturn(winner);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUserId).thenReturn(2L);
            RejectMoveOutDTO dto = new RejectMoveOutDTO();
            dto.setReason("并发重复原因");
            LeaseMoveOutApplicationVO vo = service.reject(APPLICATION_ID, dto);
            assertThat(vo.getStatus()).isEqualTo("REJECTED");
            assertThat(vo.getRejectReason()).isEqualTo("原审核原因");
        }
        verify(notificationService, never()).createForResident(any(), any(), any(), any(), any(), any(), any());
        verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
    }

    @Test
    @DisplayName("DEF-104：已驳回的同目标重试返回原结果且不再写入")
    void rejectAlreadyRejectedIsIdempotent() {
        pendingApplication.setStatus("REJECTED");
        pendingApplication.setRejectReason("原审核原因");
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            RejectMoveOutDTO dto = new RejectMoveOutDTO();
            dto.setReason("重复请求");
            assertThat(service.reject(APPLICATION_ID, dto).getRejectReason()).isEqualTo("原审核原因");
        }
        verify(moveOutMapper, never()).update(any(), any());
        verify(notificationService, never()).createForResident(any(), any(), any(), any(), any(), any(), any());
    }
    @Test
    void createRechecksLeaseAfterWaitingAndRejectsEndedLease() {
        LeaseRecord ended = new LeaseRecord();
        ended.setId(LEASE_ID); ended.setTenantId(RESIDENT_ID); ended.setStatus("MOVED_OUT");
        when(leaseMapper.selectByIdForUpdate(LEASE_ID)).thenReturn(ended);
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            var dto = new CreateMoveOutApplicationDTO(); dto.setMoveOutDate(LocalDate.now().plusDays(1));
            assertThatThrownBy(() -> service.create(LEASE_ID, dto)).isInstanceOf(BusinessException.class);
            verify(moveOutMapper, never()).insert(any(LeaseMoveOutApplication.class));
            var order = org.mockito.Mockito.inOrder(houseMapper, leaseMapper);
            order.verify(houseMapper).selectByIdForUpdate(5L);
            order.verify(leaseMapper).selectByIdForUpdate(LEASE_ID);
        }
    }

}
