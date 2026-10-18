package com.community.residence.admin.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.dto.CreateHousingTimeslotDTO;
import com.community.residence.common.housing.entity.HousingTimeslot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 房源时段管理控制器：周循环模板维护（接口设计.md 9.12.3） */
@Tag(name = "房源时段管理", description = "房源可预约时段管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminHousingTimeslotController {
    private final com.community.residence.common.housing.service.HousingTimeslotService housingTimeslotService;


    @Operation(summary = "创建房源时段", description = "同房源同星期不允许重叠")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/housings/{housingId}/timeslots")
    public ApiResponse<HousingTimeslot> create(@PathVariable Long housingId,
                                               @RequestBody @Valid CreateHousingTimeslotDTO dto) {
        return ApiResponse.success(housingTimeslotService.create(housingId, dto));
    }

    @Operation(summary = "更新房源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/housing-timeslots/{id}")
    public ApiResponse<HousingTimeslot> update(@PathVariable Long id,
                                               @RequestBody @Valid CreateHousingTimeslotDTO dto) {
        return ApiResponse.success(housingTimeslotService.update(id, dto));
    }

    @Operation(summary = "删除房源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/housing-timeslots/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        housingTimeslotService.delete(id); return ApiResponse.success();
    }
}
