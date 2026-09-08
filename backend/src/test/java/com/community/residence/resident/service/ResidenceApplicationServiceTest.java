package com.community.residence.resident.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.dto.ApproveApplicationDTO;
import com.community.residence.resident.dto.CreateApplicationDTO;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 入住申请业务逻辑测试：审核状态机、重复在审拒绝、审批联动 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidenceApplicationService 单元测试")
class ResidenceApplicationServiceTest {

    @Mock
    private ResidenceApplicationMapper applicationMapper;
    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private CommunityService communityService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ResidenceApplicationService applicationService;

    private House vacantHouse;
    private ResidenceApplication pendingApplication;

    @BeforeEach
    void setUp() {
        vacantHouse = new House();
        vacantHouse.setId(1L);
        vacantHouse.setUnitId(1L);
        vacantHouse.setCommunityId(1L);
        vacantHouse.setHouseNumber("101");
        vacantHouse.setStatus(HouseStatusConstant.VACANT);

        pendingApplication = new ResidenceApplication();
        pendingApplication.setId(1L);
        pendingApplication.setResidentId(1L);
        pendingApplication.setCommunityId(1L);
        pendingApplication.setHouseId(1L);
        pendingApplication.setRelationType("TENANT");
        pendingApplication.setStatus("PENDING");
    }

    @Test
    @DisplayName("提交申请：非空置房屋拒绝（HOUSE_NOT_VACANT）")
    void create_occupiedHouse_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            House occupied = new House();
            occupied.setId(1L);
            occupied.setCommunityId(1L);
            occupied.setStatus(HouseStatusConstant.OCCUPIED);
            when(houseMapper.selectById(1L)).thenReturn(occupied);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.HOUSE_NOT_VACANT));
        }
    }

    @Test
    @DisplayName("提交申请：同一房屋已有在审申请拒绝（APPLICATION_PENDING_EXISTS）")
    void create_duplicatePending_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(applicationMapper.selectCount(any())).thenReturn(1L);

            CreateApplicationDTO dto = new CreateApplicationDTO();
            dto.setHouseId(1L);
            dto.setRelationType("TENANT");

            assertThatThrownBy(() -> applicationService.create(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_PENDING_EXISTS));
        }
    }

    @Test
    @DisplayName("审批通过：状态 APPROVED + 房屋翻转 OCCUPIED（TENANT 建租约）")
    void approve_tenant_flipsHouseAndCreatesLease() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(2L);
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);
            when(houseMapper.selectById(1L)).thenReturn(vacantHouse);
            when(houseMapper.updateById(any(House.class))).thenReturn(1);
            when(applicationMapper.updateById(any(ResidenceApplication.class))).thenReturn(1);
            when(relationMapper.insert(any(com.community.residence.resident.entity.ResidenceRelation.class))).thenReturn(1);
            when(leaseRecordMapper.insert(any(com.community.residence.lease.entity.LeaseRecord.class))).thenReturn(1);

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.of(2026, 9, 10));
            dto.setLeaseEndDate(LocalDate.of(2027, 9, 9));
            dto.setMonthlyRent(BigDecimal.valueOf(3500));

            var vo = applicationService.approve(1L, dto);

            assertThat(vo.getStatus()).isEqualTo("APPROVED");
            assertThat(vacantHouse.getStatus()).isEqualTo("OCCUPIED");
        }
    }

    @Test
    @DisplayName("审批状态机：已通过申请重复审批拒绝（APPLICATION_ALREADY_REVIEWED）")
    void approve_alreadyReviewed_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            pendingApplication.setStatus("APPROVED");
            when(applicationMapper.selectById(1L)).thenReturn(pendingApplication);

            ApproveApplicationDTO dto = new ApproveApplicationDTO();
            dto.setLeaseStartDate(LocalDate.now());
            dto.setLeaseEndDate(LocalDate.now().plusDays(30));
            dto.setMonthlyRent(BigDecimal.ONE);

            assertThatThrownBy(() -> applicationService.approve(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.APPLICATION_ALREADY_REVIEWED));
        }
    }
}
