package com.community.residence.housing.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.service.ViewingAppointmentService;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.reservation.dto.ReservationActionDTO;
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

/** 看房预约管理控制器：居民/游客预约 + 管理端处置（接口设计.md 9.12.2） */
@Tag(name = "看房预约管理", description = "看房预约提交与处置接口")
@RestController
@RequestMapping("/api/v1/viewing-appointments")
@RequiredArgsConstructor
public class ViewingAppointmentController {

    private final ViewingAppointmentService appointmentService;

    @Operation(summary = "创建看房预约", description = "居民或游客（游客填姓名电话）；时段冲突检测")
    @PostMapping
    public ApiResponse<ViewingAppointmentVO> create(@RequestBody @Valid CreateViewingAppointmentDTO dto) {
        return ApiResponse.success(appointmentService.create(dto));
    }

    @Operation(summary = "查询预约详情")
    @GetMapping("/{id}")
    public ApiResponse<ViewingAppointmentVO> getById(@PathVariable Long id) {
        return ApiResponse.success(appointmentService.getById(id));
    }

    @Operation(summary = "预约列表（分页）", description = "居民只看本人预约；游客经详情页查询")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<ViewingAppointmentVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long housingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(appointmentService.page(page, size, status, housingId, startDate, endDate));
    }

    @Operation(summary = "确认预约", description = "待确认 → 已预约")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.confirm(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "完成预约", description = "已预约 → 已完成")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.complete(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "取消预约", description = "待确认/已预约 → 已取消")
    @PatchMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.cancel(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "标记违约", description = "已预约 → 已违约；居民违约记录留痕")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/violate")
    public ApiResponse<Void> violate(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.violate(id, dto.getReason());
        return ApiResponse.success();
    }
}
