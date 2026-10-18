package com.community.residence.common.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.entity.HousingTimeslot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 房源时段管理控制器：周循环模板维护（接口设计.md 9.12.3） */
@Tag(name = "房源时段管理", description = "房源可预约时段管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HousingTimeslotController {
    private final com.community.residence.common.housing.service.HousingTimeslotService housingTimeslotService;


    @Operation(summary = "房源时段列表", description = "公开；仅可见挂牌可读，管理端按社区权限维护")
    @GetMapping("/housings/{housingId}/timeslots")
    public ApiResponse<List<HousingTimeslot>> list(@PathVariable Long housingId,
                                                   @RequestParam(required = false) Integer dayOfWeek) {
        return ApiResponse.success(housingTimeslotService.list(housingId, dayOfWeek));
    }
}
