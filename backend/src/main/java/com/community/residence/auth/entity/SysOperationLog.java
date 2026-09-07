package com.community.residence.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志（sys_operation_log 表，仅追加，保留 ≥4 年；由 AOP 切面写入，本模块只读） */
@Data
@TableName("sys_operation_log")
@Schema(description = "操作日志")
public class SysOperationLog {

    @TableId(type = IdType.AUTO)
    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人类型：RESIDENT-居民, ADMIN-管理员, STAFF-服务人员")
    private String operatorType;

    @Schema(description = "所属社区ID（可选）")
    private Long communityId;

    @Schema(description = "操作类型")
    private String operationType;

    @Schema(description = "操作对象类型")
    private String targetType;

    @Schema(description = "操作对象ID")
    private Long targetId;

    @Schema(description = "操作内容（JSON）")
    private String content;

    @Schema(description = "操作IP")
    private String ip;

    @Schema(description = "User-Agent")
    private String userAgent;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;
}
