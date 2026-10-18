package com.community.residence.admin.reservation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.reservation.dto.ReservationActionDTO;
import com.community.residence.common.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 资源预约管理控制器：预约状态机 + 可预约时段 + 违约处置（接口设计.md 9.7） */
@Tag(name = "资源预约管理", description = "公共资源预约与违约处置接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminReservationController {

    private final ReservationService reservationService;

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

    @Operation(summary = "标记违约", description = "已预约 → 已违约；记录违约，超限自动冻结账号")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/resource-reservations/{id}/violate")
    public ApiResponse<Void> violate(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.violate(id, dto.getReason());
        return ApiResponse.success();
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
