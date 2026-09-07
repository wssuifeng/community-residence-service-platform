package com.community.residence.reservation.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.reservation.dto.CreateReservationDTO;
import com.community.residence.reservation.dto.ReservationActionDTO;
import com.community.residence.reservation.service.ReservationService;
import com.community.residence.reservation.vo.AvailableSlotVO;
import com.community.residence.reservation.vo.ReservationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 资源预约管理控制器：预约状态机 + 可预约时段 + 违约处置（接口设计.md 9.7） */
@Tag(name = "资源预约管理", description = "公共资源预约与违约处置接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "创建预约", description = "时段须落在资源可预约模板内，冲突检测")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/resource-reservations")
    public ApiResponse<ReservationVO> create(@RequestBody @Valid CreateReservationDTO dto) {
        return ApiResponse.success(reservationService.create(dto));
    }

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

    @Operation(summary = "确认预约", description = "待审核 → 已预约")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/resource-reservations/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.confirm(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "完成预约", description = "已预约 → 已完成")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/resource-reservations/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.complete(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "拒绝预约", description = "待审核 → 已拒绝")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/resource-reservations/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.reject(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "取消预约", description = "待审核/已预约 → 已取消；仅预约人本人")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/resource-reservations/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.cancel(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "标记违约", description = "已预约 → 已违约；记录违约，超限自动冻结账号")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/resource-reservations/{id}/violate")
    public ApiResponse<Void> violate(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.violate(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "查询可预约时段", description = "公开；周循环模板按日期展开")
    @GetMapping("/resources/{resourceId}/available-slots")
    public ApiResponse<List<AvailableSlotVO>> availableSlots(
            @PathVariable Long resourceId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(reservationService.availableSlots(resourceId, startDate, endDate));
    }

    @Operation(summary = "居民违约记录列表", description = "管理员查询")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/residents/{residentId}/violations")
    public ApiResponse<PageVO<Map<String, Object>>> violations(@PathVariable Long residentId,
                                                               @RequestParam(defaultValue = "1") long page,
                                                               @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(reservationService.violations(residentId, page, size));
    }
}
