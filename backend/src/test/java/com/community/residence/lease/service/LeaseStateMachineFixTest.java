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

    /* ---- DEF-013：跳级归档拦截 ---- */

    @Test
    @DisplayName("跳级归档：ACTIVE → ARCHIVED 5302 拒绝（原 200 放行）")
    void updateStatus_activeToArchived_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(leaseMapper.selectById(1L)).thenReturn(activeLease);

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
            when(leaseMapper.updateById(any(LeaseRecord.class))).thenReturn(1);

            UpdateLeaseStatusDTO dto = new UpdateLeaseStatusDTO();
            dto.setStatus("MOVED_OUT");
            leaseService.updateStatus(1L, dto);
            assertThat(activeLease.getStatus()).isEqualTo("MOVED_OUT");

            dto.setStatus("ARCHIVED");
            leaseService.updateStatus(1L, dto);
            assertThat(activeLease.getStatus()).isEqualTo("ARCHIVED");
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
            when(leaseMapper.updateById(any(LeaseRecord.class))).thenReturn(1);
            /* toVO 内部依赖：房屋名等（宽松桩） */
            when(houseMapper.selectById(1L)).thenReturn(null);

            assertThatCode(() -> leaseService.update(1L, dto())).doesNotThrowAnyException();
        }
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
