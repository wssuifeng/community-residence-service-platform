package com.community.residence.statistics.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.statistics.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 运营统计控制器：看板与专项统计（接口设计.md 9.9） */
@Tag(name = "运营统计", description = "社区运营看板与统计接口")
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Operation(summary = "运营看板", description = "统计卡片 + 图表数据；社区管理员自动限定绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard(@RequestParam(required = false) Long communityId) {
        return ApiResponse.success(statisticsService.dashboard(communityId));
    }

    @Operation(summary = "工单统计")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/work-orders")
    public ApiResponse<Map<String, Object>> workOrders(@RequestParam(required = false) Long communityId) {
        return ApiResponse.success(statisticsService.workOrderStatistics(communityId));
    }

    @Operation(summary = "居民统计")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/residents")
    public ApiResponse<Map<String, Object>> residents() {
        return ApiResponse.success(statisticsService.residentStatistics());
    }

    @Operation(summary = "资源预约统计")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/resources")
    public ApiResponse<Map<String, Object>> resources(@RequestParam(required = false) Long communityId) {
        return ApiResponse.success(statisticsService.reservationStatistics(communityId));
    }

    @Operation(summary = "社区选项列表", description = "看板社区筛选下拉")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/communities")
    public ApiResponse<List<Map<String, Object>>> communityOptions() {
        return ApiResponse.success(statisticsService.communityOptions());
    }
}
