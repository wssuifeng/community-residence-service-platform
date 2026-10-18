package com.community.residence.common.statistics.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.statistics.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 运营统计控制器：看板与专项统计（接口设计.md 9.9） */
@Tag(name = "运营统计", description = "社区运营看板与统计接口")
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Operation(summary = "本人工作量事件统计", description = "近N日本人派入次数与STAFF实际提交结果次数，按事件发生日；默认7、上限90，不推算历史负荷")
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/staff/trend")
    public ApiResponse<Map<String, Object>> staffTrend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(statisticsService.staffTrend(days));
    }
}
