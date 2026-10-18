package com.community.residence.common.reservation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.reservation.service.ReservationService;
import com.community.residence.common.reservation.vo.AvailableSlotVO;
import com.community.residence.common.reservation.vo.ReservationVO;
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

import java.time.LocalDate;
import java.util.List;

/** 资源预约管理控制器：预约状态机 + 可预约时段 + 违约处置（接口设计.md 9.7） */
@Tag(name = "资源预约管理", description = "公共资源预约与违约处置接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "查询预约详情", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/resource-reservations/{id}")
    public ApiResponse<ReservationVO> getById(@PathVariable Long id) {
        return ApiResponse.success(reservationService.getById(id));
    }

    @Operation(summary = "预约列表（分页）", description = "居民只看本人预约")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/resource-reservations")
    public ApiResponse<PageVO<ReservationVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(reservationService.page(page, size, status, resourceId, startDate, endDate));
    }

    @Operation(summary = "查询可预约时段", description = "公开；周循环模板按日期展开")
    @GetMapping("/resources/{resourceId}/available-slots")
    public ApiResponse<List<AvailableSlotVO>> availableSlots(
            @PathVariable Long resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(reservationService.availableSlots(resourceId, startDate, endDate));
    }
}
