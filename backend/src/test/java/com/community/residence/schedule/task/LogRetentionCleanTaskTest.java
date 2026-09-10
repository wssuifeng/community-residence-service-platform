package com.community.residence.schedule.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.schedule.service.TaskLogService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 日志留存清理任务测试（R58）：阈值解析、分批清理、零条数跳过、超管通知、任务日志 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LogRetentionCleanTask 单元测试")
class LogRetentionCleanTaskTest {

    @Mock
    private SysOperationLogMapper operationLogMapper;
    @Mock
    private SysUserMapper sysUserMapper;
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
    private LogRetentionCleanTask task;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysOperationLog.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    private void mockLockAcquired() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(anyLong(), any())).thenReturn(true);
    }

    /** 构造一条到期/未到期日志 */
    private SysOperationLog log(Long id, LocalDateTime createdAt) {
        SysOperationLog entry = new SysOperationLog();
        entry.setId(id);
        entry.setCreatedAt(createdAt);
        return entry;
    }

    private SysUser superAdmin(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole("SUPER_ADMIN");
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    @DisplayName("阈值 0 全清：所有日志早于当前时间，全部删除并通知超管")
    void execute_zeroRetention_cleansAllAndNotifies() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn("0");
        when(operationLogMapper.selectCount(any())).thenReturn(3L);
        /* selectList 首批返回 3 条，第二批返回空（分批循环终止条件） */
        when(operationLogMapper.selectList(any())).thenReturn(
                List.of(log(1L, LocalDateTime.now().minusDays(1)),
                        log(2L, LocalDateTime.now().minusDays(1)),
                        log(3L, LocalDateTime.now().minusDays(1))),
                List.of());
        when(operationLogMapper.delete(any(Wrapper.class))).thenReturn(3);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(superAdmin(1L), superAdmin(2L)));

        task.execute();

        verify(operationLogMapper).delete(any(Wrapper.class));
        verify(notificationService, org.mockito.Mockito.times(2)).create(
                anyLong(), isNull(), contains("操作日志留存清理"), contains("3 条"), eq("SYSTEM"), eq("TASK"), isNull());
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(3), eq(3), eq(0), isNull());
    }

    @Test
    @DisplayName("阈值边界：恰好 N 天前的日志（严格早于 deadline）被清理")
    void execute_boundary_Ndays() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn("30");
        when(operationLogMapper.selectCount(any())).thenReturn(1L);
        /* 31 天前 = 严格早于 now-30d，应清理；第二批空（循环终止） */
        when(operationLogMapper.selectList(any())).thenReturn(
                List.of(log(5L, LocalDateTime.now().minusDays(31))), List.of());
        when(operationLogMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(superAdmin(1L)));

        task.execute();

        verify(operationLogMapper).delete(any(Wrapper.class));
        verify(notificationService).create(eq(1L), isNull(), contains("清理"),
                contains("1 条"), eq("SYSTEM"), eq("TASK"), isNull());
    }

    @Test
    @DisplayName("非法阈值回退默认 730 天并继续执行")
    void execute_invalidRetention_fallsBack() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn("abc");
        when(operationLogMapper.selectCount(any())).thenReturn(0L);

        task.execute();

        /* 回退 730 天窗口下无到期日志 → 跳过且不通知 */
        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(0), eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("待删为 0：跳过且不通知，任务日志仍记录")
    void execute_noExpiredLogs_skipsNotification() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn("730");
        when(operationLogMapper.selectCount(any())).thenReturn(0L);

        task.execute();

        verify(operationLogMapper, never()).delete(any(Wrapper.class));
        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(0), eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("有清理则通知与任务日志齐备：通知标题含条数与阈值")
    void execute_withCleanup_notifiesWithCounts() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn("365");
        when(operationLogMapper.selectCount(any())).thenReturn(2L);
        when(operationLogMapper.selectList(any())).thenReturn(
                List.of(log(7L, LocalDateTime.now().minusDays(400))), List.of());
        when(operationLogMapper.delete(any(Wrapper.class))).thenReturn(2);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(superAdmin(1L)));

        task.execute();

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(1L), isNull(), eq("操作日志留存清理完成"),
                contentCaptor.capture(), eq("SYSTEM"), eq("TASK"), isNull());
        assertThat(contentCaptor.getValue())
                .contains("2 条")
                .contains("365 天")
                .contains("log.retention_days");
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(2), eq(2), eq(0), isNull());
    }

    @Test
    @DisplayName("已有实例持锁：跳过执行，不清理不通知")
    void execute_lockHeld_skips() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any())).thenReturn(false);

        task.execute();

        verify(operationLogMapper, never()).selectCount(any());
        verify(operationLogMapper, never()).delete(any(Wrapper.class));
        verify(notificationService, never()).create(anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), any());
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(0), eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("配置缺失（null）：回退默认 730 天")
    void execute_missingConfig_fallsBack() throws InterruptedException {
        mockLockAcquired();
        when(sysConfigService.getValue("log.retention_days")).thenReturn(null);
        when(operationLogMapper.selectCount(any())).thenReturn(0L);

        task.execute();

        verify(operationLogMapper, never()).delete(any(Wrapper.class));
        verify(taskLogService).record(eq(LogRetentionCleanTask.TASK_NAME), any(LocalDateTime.class),
                anyLong(), eq(0), eq(0), eq(0), isNull());
    }
}
