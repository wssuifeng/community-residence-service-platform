package com.community.residence.schedule.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 定时任务执行日志（sys_task_log 表，架构设计.md §5 任务执行监控） */
@Data
@TableName("sys_task_log")
@Schema(description = "定时任务执行日志")
public class SysTaskLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "任务名称")
    private String taskName;

    @Schema(description = "开始时间")
    private LocalDateTime startTime;

    @Schema(description = "结束时间")
    private LocalDateTime endTime;

    @Schema(description = "执行时长（毫秒）")
    private Long duration;

    @Schema(description = "执行状态：SUCCESS-成功, FAILED-失败")
    private String status;

    @Schema(description = "处理条数")
    private Integer processedCount;

    @Schema(description = "成功条数")
    private Integer successCount;

    @Schema(description = "失败条数")
    private Integer failedCount;

    @Schema(description = "告警级别：INFO-信息, WARNING-警告, ERROR-错误")
    private String alertLevel;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
