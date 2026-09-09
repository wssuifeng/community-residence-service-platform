package com.community.residence.schedule.task;

import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.entity.LeaseReminder;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.mapper.LeaseReminderMapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.schedule.service.TaskLogService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 租期到期提醒任务测试：去重唯一键、双档提醒内容、配置窗口解析 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeaseExpiryReminderTask 单元测试")
class LeaseExpiryReminderTaskTest {

    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private LeaseReminderMapper leaseReminderMapper;
    @Mock
    private SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private TaskLogService taskLogService;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private LeaseExpiryReminderTask task;

    /* 纯 Mockito 环境无 Spring 容器，需手动注册实体元数据供 Lambda 条件构造器解析列名 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LeaseRecord.class);
        TableInfoHelper.initTableInfo(assistant, SysAdminCommunity.class);
        TableInfoHelper.initTableInfo(assistant, LeaseReminder.class);
    }

    private void mockLockAcquired() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), any())).thenReturn(true);
    }

    private LeaseRecord lease(long id, LocalDate endDate) {
        LeaseRecord record = new LeaseRecord();
        record.setId(id);
        record.setTenantId(100L);
        record.setCommunityId(1L);
        record.setStatus("ACTIVE");
        record.setEndDate(endDate);
        return record;
    }

    @Test
    @DisplayName("即将到期：止期在窗口内 → EXPIRING 提醒居民与绑定管理员")
    void execute_expiring_sendsReminders() throws Exception {
        mockLockAcquired();
        when(sysConfigService.getValue(SysConfigService.KEY_LEASE_REMINDER_DAYS)).thenReturn("30");
        when(leaseRecordMapper.selectList(any())).thenReturn(
                List.of(lease(1L, LocalDate.now().plusDays(10))));
        when(leaseReminderMapper.insert(any(LeaseReminder.class))).thenReturn(1);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(
                List.of(binding(9L)));

        task.execute();

        ArgumentCaptor<LeaseReminder> captor = ArgumentCaptor.forClass(LeaseReminder.class);
        verify(leaseReminderMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("EXPIRING");
        assertThat(captor.getValue().getLeaseId()).isEqualTo(1L);
        verify(notificationService).create(eq(100L), eq(1L), eq("租住即将到期"),
                anyString(), eq("LEASE"), eq("LEASE_RECORD"), eq(1L));
        verify(notificationService).create(eq(9L), eq(1L), eq("社区租住即将到期"),
                anyString(), eq("LEASE"), eq("LEASE_RECORD"), eq(1L));
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(1), eq(1), eq(0), isNull());
    }

    @Test
    @DisplayName("已到期：止期已过 → EXPIRED 提醒")
    void execute_expired_sendsExpiredReminder() throws Exception {
        mockLockAcquired();
        when(sysConfigService.getValue(SysConfigService.KEY_LEASE_REMINDER_DAYS)).thenReturn("30");
        when(leaseRecordMapper.selectList(any())).thenReturn(
                List.of(lease(2L, LocalDate.now().minusDays(3))));
        when(leaseReminderMapper.insert(any(LeaseReminder.class))).thenReturn(1);
        when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of());

        task.execute();

        ArgumentCaptor<LeaseReminder> captor = ArgumentCaptor.forClass(LeaseReminder.class);
        verify(leaseReminderMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("EXPIRED");
        verify(notificationService).create(eq(100L), eq(1L), eq("租住已到期"),
                anyString(), eq("LEASE"), eq("LEASE_RECORD"), eq(2L));
    }

    @Test
    @DisplayName("去重：唯一键冲突 → 跳过，不重复发通知")
    void execute_duplicateKey_skips() throws Exception {
        mockLockAcquired();
        when(sysConfigService.getValue(SysConfigService.KEY_LEASE_REMINDER_DAYS)).thenReturn("30");
        when(leaseRecordMapper.selectList(any())).thenReturn(
                List.of(lease(3L, LocalDate.now().plusDays(5))));
        when(leaseReminderMapper.insert(any(LeaseReminder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        task.execute();

        verify(notificationService, never()).create(anyLong(), any(), anyString(),
                anyString(), anyString(), anyString(), any());
        verify(taskLogService).record(anyString(), any(), anyLong(), eq(1), eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("窗口配置非法：回退默认 30 天，任务不失败")
    void execute_invalidConfig_fallsBackToDefault() throws Exception {
        mockLockAcquired();
        when(sysConfigService.getValue(SysConfigService.KEY_LEASE_REMINDER_DAYS)).thenReturn("abc");
        when(leaseRecordMapper.selectList(any())).thenReturn(List.of());

        task.execute();

        verify(taskLogService).record(anyString(), any(), anyLong(), eq(0), eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("锁竞争：未抢到锁 → 跳过执行，不写业务日志")
    void execute_lockNotAcquired_skips() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(false);

        task.execute();

        verify(leaseRecordMapper, never()).selectList(any());
        verify(taskLogService).record(anyString(), any(), anyLong(),
                eq(0), eq(0), eq(0), isNull());
    }

    private SysAdminCommunity binding(long adminId) {
        SysAdminCommunity binding = new SysAdminCommunity();
        binding.setAdminId(adminId);
        binding.setCommunityId(1L);
        return binding;
    }
}
