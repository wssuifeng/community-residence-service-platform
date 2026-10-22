package com.community.residence.common.housing.service;

import com.community.residence.common.auth.service.TokenRevocationService;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.reservation.entity.ViolationRecord;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DEF-161：现行§9.12.2.7要求统一计数、达到阈值冻结并吊销旧令牌。 */
@ExtendWith(MockitoExtension.class)
class ViewingViolationDispositionTest {
    @org.mockito.Spy private com.community.residence.common.messaging.service.AfterCommitDeliveryService afterCommitDelivery = new com.community.residence.common.messaging.service.AfterCommitDeliveryService();
    @Mock ViewingAppointmentMapper appointmentMapper;
    @Mock ResidentMapper residentMapper;
    @Mock ViolationRecordMapper violationRecordMapper;
    @Mock SysConfigService sysConfigService;
    @Mock NotificationService notificationService;
    @Mock TokenRevocationService tokenRevocationService;
    @InjectMocks ViewingAppointmentService service;

    private Resident resident(String status) {
        Resident resident = new Resident(); resident.setId(5L); resident.setStatus(status); return resident;
    }
    private ViewingAppointment appointment(Long userId) {
        ViewingAppointment a = new ViewingAppointment(); a.setId(1L); a.setUserId(userId); a.setCommunityId(2L);
        a.setStatus("RESERVED"); a.setAppointmentDate(LocalDate.of(2026, 10, 11));
        when(appointmentMapper.selectByIdForUpdate(1L)).thenReturn(a); return a;
    }
    @Test void thresholdFreezesAndRevokesAndLeavesDisposition() {
        appointment(5L); Resident r = resident(CommonStatus.ACTIVE);
        lenient().when(residentMapper.selectByIdForUpdate(5L)).thenReturn(r);
        lenient().when(violationRecordMapper.selectIdsByUserIdForUpdate(5L)).thenReturn(java.util.List.of(1L, 2L, 3L));
        lenient().when(sysConfigService.getValue("violation.max_count")).thenReturn("3");
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            service.violate(1L, "未到场");
            assertEquals(CommonStatus.FROZEN, r.getStatus());
            verify(tokenRevocationService).revokeResident(5L);
            verify(violationRecordMapper).updateById(argThat((ViolationRecord record) -> record.getPunishment().contains("冻结")));
        }
    }
    @Test void belowThresholdOnlyRecords() {
        appointment(5L); Resident r = resident(CommonStatus.ACTIVE);
        lenient().when(residentMapper.selectByIdForUpdate(5L)).thenReturn(r);
        lenient().when(violationRecordMapper.selectIdsByUserIdForUpdate(5L)).thenReturn(java.util.List.of(1L, 2L));
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            service.violate(1L, "未到场");
            assertEquals(CommonStatus.ACTIVE, r.getStatus()); verify(tokenRevocationService, never()).revokeResident(anyLong());
        }
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"invalid", "0", "-1"})
    void invalidThresholdFallsBackToThree(String config) {
        appointment(5L); Resident r = resident(CommonStatus.ACTIVE);
        lenient().when(residentMapper.selectByIdForUpdate(5L)).thenReturn(r);
        lenient().when(violationRecordMapper.selectIdsByUserIdForUpdate(5L)).thenReturn(java.util.List.of(1L, 2L, 3L));
        lenient().when(sysConfigService.getValue("violation.max_count")).thenReturn(config);
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            service.violate(1L, "未到场"); assertEquals(CommonStatus.FROZEN, r.getStatus());
        }
    }
    @Test void duplicateMarkDoesNotAddAnotherViolation() {
        ViewingAppointment a = appointment(5L); a.setStatus("VIOLATED");
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            assertThrows(com.community.residence.common.web.exception.BusinessException.class, () -> service.violate(1L, "重复"));
            verifyNoInteractions(residentMapper, violationRecordMapper, tokenRevocationService);
        }
    }
    @Test void frozenResidentDoesNotRevokeAgain() {
        appointment(5L); Resident r = resident(CommonStatus.FROZEN);
        when(residentMapper.selectByIdForUpdate(5L)).thenReturn(r);
        when(violationRecordMapper.selectIdsByUserIdForUpdate(5L)).thenReturn(java.util.List.of(1L, 2L, 3L));
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            service.violate(1L, "未到场"); verify(tokenRevocationService, never()).revokeResident(anyLong());
        }
    }
    @Test void historicalGuestDoesNotCountOrFreezeOrNotifyResident() {
        appointment(null);
        try(var security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole("ADMIN")).thenReturn(true);
            service.violate(1L, "历史匿名未到场");
            verifyNoInteractions(residentMapper, violationRecordMapper, tokenRevocationService, notificationService);
        }
    }
}
