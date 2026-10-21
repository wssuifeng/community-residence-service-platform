package com.community.residence.common.lease.service;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.lease.dto.CreateLeaseDTO;
import com.community.residence.common.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 租住业务逻辑测试：状态机流转表、到期标注、租期校验 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaseService 单元测试")
class LeaseServiceTest {
    @org.mockito.Mock private com.community.residence.common.lease.service.LeaseContractTermService termService;

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

    @Mock private com.community.residence.common.payment.mapper.LeasePaymentMapper paymentMapper;
    @Mock private com.community.residence.common.resident.mapper.ResidenceRelationMapper relationMapper;
    @Mock private com.community.residence.common.agreement.mapper.LeaseAgreementMapper agreementMapper;
    @Mock private com.community.residence.common.conversation.service.ConversationService conversationService;
    @Mock private com.community.residence.common.resident.service.ResidenceDependencyService residenceDependencyService;
    @Mock private com.community.residence.common.community.service.HouseStateService houseStateService;

    @InjectMocks
    private LeaseService leaseService;

    private LeaseRecord activeLease;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), com.community.residence.common.agreement.entity.LeaseAgreement.class);
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

    @Test
    @DisplayName("OCR DEF-108：状态端点参数校验放行 TO_SIGN（Bean Validation 直测）")
    void updateLeaseStatusDto_patternAcceptsToSign() {
        jakarta.validation.Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
        dto.setStatus("TO_SIGN");
        dto.setRemark("测试");
        Set<jakarta.validation.ConstraintViolation<UpdateLeaseStatusDTO>> violations =
                validator.validate(dto);
        assertThat(violations).isEmpty();

        /* 非法值仍被拒：放行 TO_SIGN 不等于放开枚举白名单 */
        dto.setStatus("INVALID");
        assertThat(validator.validate(dto)).isNotEmpty();
    }

    @Test
    @DisplayName("状态机：PENDING 不可直达 ARCHIVED（STATE_TRANSITION_INVALID 5004）")
    void updateStatus_illegalTransition_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            LeaseRecord pending = new LeaseRecord();
            pending.setId(1L);
            pending.setCommunityId(1L);
            pending.setStatus("PENDING");
            when(leaseMapper.selectById(1L)).thenReturn(pending);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(pending);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("ARCHIVED");
            assertThatThrownBy(() -> leaseService.updateStatus(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ARCHIVED");
        }
    }

    @Test
    @DisplayName("状态机：PENDING → TO_SIGN → ACTIVE → MOVED_OUT 线性合法流转（DEF-115：PENDING 不可直达 ACTIVE）")
    void updateStatus_legalTransitions_pass() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            LeaseRecord pending = new LeaseRecord();
            pending.setId(1L);
            pending.setCommunityId(1L);
            pending.setStatus("PENDING");
            when(leaseMapper.selectById(1L)).thenReturn(pending);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(pending);
            when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("TO_SIGN");
            leaseService.updateStatus(1L, dto);
            assertThat(pending.getStatus()).isEqualTo("TO_SIGN");

            pending.setHouseId(1L); pending.setTenantId(1L);
            com.community.residence.common.community.entity.House vacant = new com.community.residence.common.community.entity.House(); vacant.setId(1L); vacant.setStatus("VACANT");
            when(houseMapper.selectById(1L)).thenReturn(vacant);
            pending.setAgreementStatus("SIGNED");
            leaseService.applyTransition(pending, "ACTIVE", "居民已同意具体协议");
            assertThat(pending.getStatus()).isEqualTo("ACTIVE");

            dto.setStatus("MOVED_OUT");
            leaseService.updateStatus(1L, dto);
            assertThat(pending.getStatus()).isEqualTo("MOVED_OUT");
        }
    }

    @Test
    @DisplayName("到期标注：ACTIVE 且结束日在 30 天内 → EXPIRING")
    void toVO_expiringFlag() {
        when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            var vo = leaseService.getById(1L);
            assertThat(vo.getExpiryFlag()).isEqualTo("EXPIRING");
        }
    }

    @Test
    @DisplayName("到期标注：结束日已过 → EXPIRED")
    void toVO_expiredFlag() {
        activeLease.setEndDate(LocalDate.now().minusDays(1));
        when(leaseMapper.selectById(1L)).thenReturn(activeLease);
            org.mockito.Mockito.lenient().when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            var vo = leaseService.getById(1L);
            assertThat(vo.getExpiryFlag()).isEqualTo("EXPIRED");
        }
    }

    @Test
    @DisplayName("创建租约：同一居民与房屋已有进行中租约时拒绝重复登记")
    void create_existingLease_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            when(residentMapper.selectById(1L)).thenReturn(new com.community.residence.common.resident.entity.Resident());
            com.community.residence.common.community.entity.House house = new com.community.residence.common.community.entity.House();
            house.setId(1L);
            house.setCommunityId(1L);
            when(houseMapper.selectById(1L)).thenReturn(house);
            when(leaseMapper.selectCount(any())).thenReturn(1L);

            CreateLeaseDTO dto = new CreateLeaseDTO();
            dto.setResidentId(1L);
            dto.setHouseId(1L);
            dto.setStartDate(LocalDate.of(2026, 10, 10));
            dto.setEndDate(LocalDate.of(2027, 10, 9));
            dto.setMonthlyRent(BigDecimal.valueOf(3000));

            assertThatThrownBy(() -> leaseService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("进行中的租约");
            verify(leaseMapper, never()).insert(any(LeaseRecord.class));
        }
    }

    @Test
    @DisplayName("创建租约：结束日期早于开始日期拒绝")
    void create_endBeforeStart_throws() {
        try (MockedStatic<com.community.residence.common.security.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            when(residentMapper.selectById(1L)).thenReturn(new com.community.residence.common.resident.entity.Resident());
            com.community.residence.common.community.entity.House house = new com.community.residence.common.community.entity.House();
            house.setCommunityId(1L);
            when(houseMapper.selectById(1L)).thenReturn(house);

            CreateLeaseDTO dto = new CreateLeaseDTO();
            dto.setResidentId(1L);
            dto.setHouseId(1L);
            dto.setStartDate(LocalDate.of(2026, 10, 10));
            dto.setEndDate(LocalDate.of(2026, 10, 1));
            dto.setMonthlyRent(BigDecimal.valueOf(3000));

            assertThatThrownBy(() -> leaseService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("结束日期必须晚于开始日期");
        }
    }
    @Test void directLeaseMoveOutCannotBypassDependentConfirmation() {
        when(leaseMapper.update(any(LeaseRecord.class),any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        var root = new com.community.residence.common.resident.entity.ResidenceRelation();
        root.setId(99L);root.setLeaseId(activeLease.getId());
        when(relationMapper.selectList(any())).thenReturn(java.util.List.of(root));
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INVALID_PARAM,"请确认整户"))
            .when(residenceDependencyService).endMembers(org.mockito.ArgumentMatchers.eq(99L),any(),org.mockito.ArgumentMatchers.eq(false));
        assertThatThrownBy(()->leaseService.applyTransition(activeLease,"MOVED_OUT","迁出"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("整户");
        verify(relationMapper,never()).updateById(any(com.community.residence.common.resident.entity.ResidenceRelation.class));
    }
    @Test void directLeaseMoveOutPassesExplicitConfirmation() {
        when(leaseMapper.update(any(LeaseRecord.class),any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        var root = new com.community.residence.common.resident.entity.ResidenceRelation();
        root.setId(99L);root.setLeaseId(activeLease.getId());
        when(relationMapper.selectList(any())).thenReturn(java.util.List.of(root));
        leaseService.applyTransition(activeLease,"MOVED_OUT","迁出",true);
        verify(residenceDependencyService).endMembers(org.mockito.ArgumentMatchers.eq(99L),any(),org.mockito.ArgumentMatchers.eq(true));
        verify(relationMapper).updateById(root);
    }

    @Test void editingLegacyLeaseDoesNotInventMonthsOrReplaceDatesWithToday() {
        var oldStart = LocalDate.of(2025, 5, 10);
        var oldEnd = LocalDate.of(2026, 8, 19);
        activeLease.setStartDate(oldStart); activeLease.setEndDate(oldEnd);
        activeLease.setContractMonths(null); activeLease.setFirstPaymentMonths(null); activeLease.setPaymentCycleMonths(null);
        when(leaseMapper.selectByIdForUpdate(1L)).thenReturn(activeLease);
        var dto = new CreateLeaseDTO(); dto.setHouseId(1L); dto.setMonthlyRent(new BigDecimal("2000"));
        try (var security = mockStatic(com.community.residence.common.security.context.SecurityUtils.class)) {
            leaseService.update(1L, dto);
        }
        assertThat(activeLease.getStartDate()).isEqualTo(oldStart);
        assertThat(activeLease.getEndDate()).isEqualTo(oldEnd);
        assertThat(activeLease.getContractMonths()).isNull();
        assertThat(activeLease.getFirstPaymentMonths()).isNull();
    }
}
