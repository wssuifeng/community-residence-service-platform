package com.community.residence.lease.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.lease.dto.CreateLeaseDTO;
import com.community.residence.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-013/017 修复回归（R16 线性流转 + 归档后不可修改）：
 * ACTIVE 不可跳级 ARCHIVED；ARCHIVED 租约 PUT 修改被拒。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("租约状态机与归档禁改修复回归（DEF-013/017）")
class LeaseStateMachineFixTest {

    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private com.community.residence.messaging.service.NotificationService notificationService;
    @Mock
    private com.community.residence.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;

    @Mock private com.community.residence.payment.mapper.LeasePaymentMapper paymentMapper;
    @Mock private com.community.residence.resident.mapper.ResidenceRelationMapper relationMapper;
    @Mock private com.community.residence.agreement.mapper.LeaseAgreementMapper agreementMapper;
    @Mock private com.community.residence.conversation.service.ConversationService conversationService;
    @InjectMocks
    private LeaseService leaseService;

    private LeaseRecord activeLease;

    @BeforeEach
    void setUp() {
        for (Class<?> entity : new Class<?>[]{com.community.residence.resident.entity.ResidenceRelation.class, com.community.residence.payment.entity.LeasePayment.class}) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), entity);
        }
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), com.community.residence.agreement.entity.LeaseAgreement.class);
        org.mockito.Mockito.lenient().when(paymentMapper.selectCount(org.mockito.ArgumentMatchers.any())).thenReturn(1L);

        activeLease = new LeaseRecord();
        activeLease.setId(1L);
        activeLease.setTenantId(1L);
        activeLease.setCommunityId(1L);
        activeLease.setHouseId(1L);
        activeLease.setStatus("ACTIVE");
        activeLease.setStartDate(LocalDate.now().minusDays(100));
        activeLease.setEndDate(LocalDate.now().plusDays(20));
    }

    /* ---- DEF-013：跳级归档拦截 ---- */

    @Test
    @DisplayName("跳级归档：ACTIVE → ARCHIVED 5302 拒绝（原 200 放行）")
    void updateStatus_activeToArchived_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("ARCHIVED");
            assertThatThrownBy(() -> leaseService.updateStatus(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ARCHIVED");
            assertThat(activeLease.getStatus()).isEqualTo("ACTIVE");
        }
    }

    @Test
    @DisplayName("线性流转：ACTIVE → MOVED_OUT → ARCHIVED 两步合法")
    void updateStatus_linearPath_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
            when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("MOVED_OUT");
            leaseService.updateStatus(1L, dto);
            assertThat(activeLease.getStatus()).isEqualTo("MOVED_OUT");

            dto.setStatus("ARCHIVED");
            leaseService.updateStatus(1L, dto);
            assertThat(activeLease.getStatus()).isEqualTo("ARCHIVED");
        }
    }

    /* ---- R66 v1.8：审核通过 → 待签约 → 生效（房态在生效时才翻转） ---- */

    @Test
    @DisplayName("待签约流转：PENDING → TO_SIGN（房态不动）→ ACTIVE（同步置房屋已入住）")
    void updateStatus_pendingToSignThenActive_flipsHouseOnActiveOnly() {
        LeaseRecord pendingLease = new LeaseRecord();
        pendingLease.setId(2L);
        pendingLease.setTenantId(1L);
        pendingLease.setCommunityId(1L);
        pendingLease.setHouseId(5L);
        pendingLease.setStatus("PENDING");
        pendingLease.setStartDate(LocalDate.now());
        pendingLease.setEndDate(LocalDate.now().plusMonths(12));

        com.community.residence.community.entity.House house =
                new com.community.residence.community.entity.House();
        house.setId(5L);
        house.setCommunityId(1L);
        house.setStatus("VACANT");

        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(2L)).thenReturn(pendingLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(2L)).thenReturn(pendingLease);
            when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
            when(houseMapper.selectById(5L)).thenReturn(house);
            when(houseMapper.updateById(any(com.community.residence.community.entity.House.class)))
                    .thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("TO_SIGN");
            leaseService.updateStatus(2L, dto);
            assertThat(pendingLease.getStatus()).isEqualTo("TO_SIGN");
            /* 到待签约为止房态保持空置（R66 v1.8：审核通过不置已入住） */
            assertThat(house.getStatus()).isEqualTo("VACANT");

            pendingLease.setAgreementStatus("SIGNED");
            leaseService.applyTransition(pendingLease, "ACTIVE", "居民已同意具体协议");
            assertThat(pendingLease.getStatus()).isEqualTo("ACTIVE");
            assertThat(house.getStatus()).isEqualTo("OCCUPIED");
            verify(houseMapper).updateById(any(com.community.residence.community.entity.House.class));
        }
    }

    @Test
    @DisplayName("待签约驳回：TO_SIGN → REJECTED 合法")
    void updateStatus_toSignRejected_ok() {
        activeLease.setStatus("TO_SIGN");
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
            when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("REJECTED");
            leaseService.updateStatus(1L, dto);
            assertThat(activeLease.getStatus()).isEqualTo("REJECTED");
        }
    }

    /* ---- DEF-017：归档禁改 ---- */

    @Test
    @DisplayName("归档租约修改：PUT 业务字段被拒（原 200 改写）")
    void update_archivedLease_throws() {
        activeLease.setStatus("ARCHIVED");
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);

            assertThatThrownBy(() -> leaseService.update(1L, dto()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("归档");
            verify(leaseMapper, never()).updateById(any(LeaseRecord.class));
        }
    }

    @Test
    @DisplayName("在住租约修改：PUT 正常（合法路径不回归）")
    void update_activeLease_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
            when(leaseMapper.updateById(any(LeaseRecord.class))).thenReturn(1);
            /* toVO 内部依赖：房屋名等（宽松桩） */
            when(houseMapper.selectById(1L)).thenReturn(null);

            assertThatCode(() -> leaseService.update(1L, dto())).doesNotThrowAnyException();
        }
    }

    /* ---- DEF-108/DEF-115：TO_SIGN 放行 + 直达生效拦截 + 并发幂等 ---- */

    @Test
    @DisplayName("OCR DEF-115：PENDING → ACTIVE 直达被拒（v1.8 无此边，须先经 TO_SIGN）")
    void updateStatus_pendingDirectActive_throws() {
        LeaseRecord pendingLease = new LeaseRecord();
        pendingLease.setId(3L);
        pendingLease.setTenantId(1L);
        pendingLease.setCommunityId(1L);
        pendingLease.setHouseId(5L);
        pendingLease.setStatus("PENDING");

        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(3L)).thenReturn(pendingLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(3L)).thenReturn(pendingLease);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("ACTIVE");
            assertThatThrownBy(() -> leaseService.updateStatus(3L, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.STATE_TRANSITION_INVALID));
            assertThat(pendingLease.getStatus()).isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("OCR DEF-115：房屋锁后重读已为目标状态 → 幂等返回不重复留痕")
    void applyTransition_concurrentSameTarget_idempotentNoRepeatLog() {
        LeaseRecord toSignLease = new LeaseRecord();
        toSignLease.setId(4L);
        toSignLease.setTenantId(1L);
        toSignLease.setCommunityId(1L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setAgreementStatus("SIGNED");

        /* 房屋锁后重读已是 ACTIVE（并发赢家已完成流转） → 幂等返回 */
        LeaseRecord winner = new LeaseRecord();
        winner.setId(4L);
        winner.setStatus("ACTIVE");
        when(leaseMapper.selectByIdForUpdate(4L)).thenReturn(winner);

        leaseService.applyTransition(toSignLease, "ACTIVE", "测试");

        verify(changeLogService, never())
                .logStatus(anyLong(), anyString(), anyString(), anyString());
        verify(houseMapper, never()).updateById(any(com.community.residence.community.entity.House.class));
    }

    @Test
    @DisplayName("OCR DEF-115：房屋锁后重读状态漂移（非目标状态）→ STATE_TRANSITION_INVALID")
    void applyTransition_concurrentDrift_rejected() {
        LeaseRecord toSignLease = new LeaseRecord();
        toSignLease.setId(4L);
        toSignLease.setTenantId(1L);
        toSignLease.setCommunityId(1L);
        toSignLease.setHouseId(5L);
        toSignLease.setStatus("TO_SIGN");
        toSignLease.setAgreementStatus("SIGNED");

        /* 房屋锁后重读为 REJECTED（并发被驳回）→ 按并发状态冲突拒绝 */
        LeaseRecord drifted = new LeaseRecord();
        drifted.setId(4L);
        drifted.setStatus("REJECTED");
        when(leaseMapper.selectByIdForUpdate(4L)).thenReturn(drifted);

        assertThatThrownBy(() -> leaseService.applyTransition(toSignLease, "ACTIVE", "测试"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_TRANSITION_INVALID));
        verify(changeLogService, never())
                .logStatus(anyLong(), anyString(), anyString(), anyString());
    }

    private CreateLeaseDTO dto() {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setResidentId(1L);
        dto.setHouseId(1L);
        dto.setStartDate(LocalDate.now().minusDays(100));
        dto.setEndDate(LocalDate.now().plusDays(30));
        dto.setMonthlyRent(new BigDecimal("3000"));
        dto.setDeposit(new BigDecimal("6000"));
        return dto;
    }
}
