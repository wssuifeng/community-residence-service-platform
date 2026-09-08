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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 租住业务逻辑测试：状态机流转表、到期标注、租期校验 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaseService 单元测试")
class LeaseServiceTest {

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

    @InjectMocks
    private LeaseService leaseService;

    private LeaseRecord activeLease;

    @BeforeEach
    void setUp() {
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
    @DisplayName("状态机：PENDING 不可直达 ARCHIVED（STATE_TRANSITION_INVALID 5004）")
    void updateStatus_illegalTransition_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            LeaseRecord pending = new LeaseRecord();
            pending.setId(1L);
            pending.setCommunityId(1L);
            pending.setStatus("PENDING");
            when(leaseMapper.selectById(1L)).thenReturn(pending);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("ARCHIVED");
            assertThatThrownBy(() -> leaseService.updateStatus(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ARCHIVED");
        }
    }

    @Test
    @DisplayName("状态机：PENDING → ACTIVE → MOVED_OUT 合法流转")
    void updateStatus_legalTransitions_pass() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            LeaseRecord pending = new LeaseRecord();
            pending.setId(1L);
            pending.setCommunityId(1L);
            pending.setStatus("PENDING");
            when(leaseMapper.selectById(1L)).thenReturn(pending);
            when(leaseMapper.updateById(any(LeaseRecord.class))).thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("ACTIVE");
            leaseService.updateStatus(1L, dto);
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
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            var vo = leaseService.getById(1L);
            assertThat(vo.getExpiryFlag()).isEqualTo("EXPIRING");
        }
    }

    @Test
    @DisplayName("到期标注：结束日已过 → EXPIRED")
    void toVO_expiredFlag() {
        activeLease.setEndDate(LocalDate.now().minusDays(1));
        when(leaseMapper.selectById(1L)).thenReturn(activeLease);
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            var vo = leaseService.getById(1L);
            assertThat(vo.getExpiryFlag()).isEqualTo("EXPIRED");
        }
    }

    @Test
    @DisplayName("创建租约：结束日期早于开始日期拒绝")
    void create_endBeforeStart_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(residentMapper.selectById(1L)).thenReturn(new com.community.residence.resident.entity.Resident());
            com.community.residence.community.entity.House house = new com.community.residence.community.entity.House();
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
}
