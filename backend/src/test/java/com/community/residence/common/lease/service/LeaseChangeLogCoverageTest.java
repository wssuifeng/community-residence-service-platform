package com.community.residence.common.lease.service;

import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.lease.entity.LeaseChangeLog;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseChangeLogMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 字段级历史与操作人快照组合；不调用支付或伪造渠道结果。 */
@ExtendWith(MockitoExtension.class)
class LeaseChangeLogCoverageTest {
    @Mock LeaseChangeLogMapper changeLogMapper;
    @Mock ResidentMapper residentMapper;
    @Mock SysUserMapper sysUserMapper;
    @InjectMocks LeaseChangeLogService service;

    private LeaseRecord lease() {
        LeaseRecord result = new LeaseRecord();
        result.setId(81L);
        result.setStartDate(LocalDate.of(2026, 10, 13));
        result.setEndDate(LocalDate.of(2027, 10, 13));
        result.setMonthlyRent(new BigDecimal("1500.00"));
        result.setDeposit(new BigDecimal("3000.00"));
        result.setRemark("原约定");
        return result;
    }

    private List<LeaseChangeLog> captured(int count) {
        ArgumentCaptor<LeaseChangeLog> records = ArgumentCaptor.forClass(LeaseChangeLog.class);
        verify(changeLogMapper, times(count)).insert(records.capture());
        return records.getAllValues();
    }

    @Test void equalMoneyWithDifferentScaleDoesNotCreateNoise() {
        LeaseRecord before = lease(), after = lease();
        after.setMonthlyRent(new BigDecimal("1500"));
        after.setDeposit(new BigDecimal("3000.000"));
        service.logAttributeChanges(before, after, "无修改");
        verifyNoInteractions(changeLogMapper, residentMapper, sysUserMapper);
    }

    @Test void onlyChangedFieldsRetainTheirExactBeforeAndAfter() {
        LeaseRecord before = lease(), after = lease();
        after.setEndDate(LocalDate.of(2028, 10, 13));
        after.setMonthlyRent(new BigDecimal("1600.00"));
        after.setRemark(" ");
        service.logAttributeChanges(before, after, "调整约定");
        List<LeaseChangeLog> logs = captured(3);
        assertThat(logs).extracting(LeaseChangeLog::getFieldName)
                .containsExactly("endDate", "monthlyRent", "remark");
        assertThat(logs).extracting(LeaseChangeLog::getOldValue)
                .containsExactly("2027-10-13", "1500", "原约定");
        assertThat(logs).extracting(LeaseChangeLog::getNewValue)
                .containsExactly("2028-10-13", "1600", null);
        assertThat(logs).allSatisfy(log -> {
            assertThat(log.getChangeType()).isEqualTo("ATTRIBUTE");
            assertThat(log.getLeaseId()).isEqualTo(81L);
            assertThat(log.getReason()).isEqualTo("调整约定");
            assertThat(log.getOperatorType()).isEqualTo("SYSTEM");
        });
    }

    @Test void allNullableFieldsRecordRealTransitionsWithoutInventingZeroAmounts() {
        LeaseRecord before = new LeaseRecord(), after = lease();
        service.logRenew(before, after, "续期修改");
        List<LeaseChangeLog> logs = captured(5);
        assertThat(logs).extracting(LeaseChangeLog::getOldValue).containsOnlyNulls();
        assertThat(logs).extracting(LeaseChangeLog::getNewValue)
                .containsExactly("2026-10-13", "2027-10-13", "1500", "3000", "原约定");
        assertThat(logs).extracting(LeaseChangeLog::getChangeType).containsOnly("RENEW");
    }

    @Test void clearingAmountsAndDatesDoesNotRenderTheStringNull() {
        LeaseRecord after = new LeaseRecord(); after.setId(81L);
        service.logAttributeChanges(lease(), after, "清空");
        assertThat(captured(5)).extracting(LeaseChangeLog::getNewValue).containsOnlyNulls();
    }

    @Test void absentSnapshotsAreNotInvented() {
        service.logAttributeChanges(null, lease(), "无原件");
        service.logRenew(lease(), null, "无目标");
        verifyNoInteractions(changeLogMapper, residentMapper, sysUserMapper);
    }

    @Test void createSummaryKeepsPlainAmountsAndSystemIdentity() {
        service.logCreate(lease(), "登记");
        LeaseChangeLog entry = captured(1).get(0);
        assertThat(entry.getNewValue()).isEqualTo("租期 2026-10-13 ~ 2027-10-13，月租 1500，押金 3000");
        assertThat(entry.getOperatorId()).isNull();
        assertThat(entry.getOperatorName()).isEqualTo("系统");
    }

    @ParameterizedTest
    @CsvSource({"RESIDENT,true", "RESIDENT,false", "ADMIN,true", "ADMIN,false", "SUPER_ADMIN,true"})
    void actorNameUsesTheCorrectIdentitySpace(String role, boolean present) {
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "fallback", role, Set.of()));
            if ("RESIDENT".equals(role)) {
                Resident resident = new Resident(); resident.setRealName("居民甲");
                when(residentMapper.selectById(9L)).thenReturn(present ? resident : null);
            } else {
                SysUser user = new SysUser(); user.setRealName("管理甲");
                when(sysUserMapper.selectById(9L)).thenReturn(present ? user : null);
            }
            service.logStatus(81L, "ACTIVE", "MOVED_OUT", "迁出");
            LeaseChangeLog entry = captured(1).get(0);
            assertThat(entry.getOperatorId()).isEqualTo(9L);
            assertThat(entry.getOperatorType()).isEqualTo("RESIDENT".equals(role) ? "RESIDENT" : "ADMIN");
            assertThat(entry.getOperatorName()).isEqualTo(present ? ("RESIDENT".equals(role) ? "居民甲" : "管理甲") : "fallback");
            if ("RESIDENT".equals(role)) verifyNoInteractions(sysUserMapper);
            else verifyNoInteractions(residentMapper);
        }
    }

    @Test void blankAdminNameFallsBackToUsername() {
        SysUser user = new SysUser(); user.setRealName(" ");
        when(sysUserMapper.selectById(9L)).thenReturn(user);
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUser).thenReturn(new UserContext(9L, "admin-alias", "ADMIN", Set.of()));
            service.logAgreement(81L, "ISSUED", "正文快照已发出");
            assertThat(captured(1).get(0).getOperatorName()).isEqualTo("admin-alias");
        }
    }

    @Test void agreementAndStatusActionsRetainTypeAndDetail() {
        service.logAgreement(81L, "REVOKED", "居民未同意");
        service.logStatus(81L, "PENDING", "CANCELLED", "取消申请");
        List<LeaseChangeLog> logs = captured(2);
        assertThat(logs).extracting(LeaseChangeLog::getChangeType).containsExactly("AGREEMENT", "STATUS");
        assertThat(logs).extracting(LeaseChangeLog::getNewValue).containsExactly("REVOKED", "CANCELLED");
        assertThat(logs).extracting(LeaseChangeLog::getReason).containsExactly("居民未同意", "取消申请");
    }

    @Test void listReturnsStoredSnapshotInsteadOfLookingUpCurrentNames() {
        LeaseChangeLog log = new LeaseChangeLog(); log.setLeaseId(81L); log.setOperatorName("历史姓名");
        log.setOldValue("1500"); log.setNewValue("1600"); log.setFieldName("monthlyRent");
        when(changeLogMapper.selectList(any())).thenReturn(List.of(log));
        var rows = service.list(81L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getOperatorName()).isEqualTo("历史姓名");
        assertThat(rows.get(0).getOldValue()).isEqualTo("1500");
        verifyNoInteractions(residentMapper, sysUserMapper);
    }
}
