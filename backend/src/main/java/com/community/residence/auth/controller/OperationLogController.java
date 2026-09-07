package com.community.residence.auth.controller;

import com.community.residence.auth.service.OperationLogService;
import com.community.residence.auth.vo.OperationLogVO;
import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 操作日志查询控制器（接口设计.md 9.10.3；写入由 AOP 切面覆盖） */
@Tag(name = "操作日志", description = "操作留痕查询接口")
@RestController
@RequestMapping("/api/v1/operation-logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    @Operation(summary = "操作日志列表（分页）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<OperationLogVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String operatorType,
            @RequestParam(required = false) String operationType,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.success(operationLogService.page(page, size, operatorId, operatorType,
                operationType, targetType, startTime, endTime));
    }

    @Operation(summary = "操作日志详情")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> getById(@PathVariable Long id) {
        return ApiResponse.success(operationLogService.getById(id));
    }
}
