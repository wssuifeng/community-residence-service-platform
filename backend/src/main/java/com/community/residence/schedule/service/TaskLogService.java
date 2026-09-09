package com.community.residence.schedule.service;

import com.community.residence.schedule.entity.SysTaskLog;
import com.community.residence.schedule.mapper.SysTaskLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 定时任务执行日志：所有任务在执行收尾处统一落 sys_task_log
 * （架构设计.md §5 任务执行监控，SUCCESS/FAILED + 耗时 + 处理计数）。
 * 写日志自身失败不影响任务结果（日志降级为 WARN）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLogService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String ALERT_INFO = "INFO";
    public static final String ALERT_ERROR = "ERROR";

    /** 错误信息截断上限，防止异常堆栈撑爆 TEXT 列 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 2000;

    private final SysTaskLogMapper sysTaskLogMapper;

    /**
     * 记录一次任务执行。errorMessage 为空视为 SUCCESS。
     */
    public void record(String taskName, java.time.LocalDateTime startTime, long durationMs,
                       Integer processedCount, Integer successCount, Integer failedCount,
                       String errorMessage) {
        try {
            SysTaskLog entry = new SysTaskLog();
            entry.setTaskName(taskName);
            entry.setStartTime(startTime);
            entry.setEndTime(startTime.plusNanos(durationMs * 1_000_000));
            entry.setDuration(durationMs);
            entry.setStatus(errorMessage == null ? STATUS_SUCCESS : STATUS_FAILED);
            entry.setAlertLevel(errorMessage == null ? ALERT_INFO : ALERT_ERROR);
            entry.setProcessedCount(processedCount);
            entry.setSuccessCount(successCount);
            entry.setFailedCount(failedCount);
            entry.setErrorMessage(truncate(errorMessage));
            sysTaskLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("任务日志写入失败：taskName={}", taskName, e);
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= ERROR_MESSAGE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_MAX_LENGTH);
    }
}
