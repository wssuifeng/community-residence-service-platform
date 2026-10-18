package com.community.residence.resident.reservation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.reservation.dto.CreateReservationDTO;
import com.community.residence.common.reservation.dto.ReservationActionDTO;
import com.community.residence.common.reservation.service.ReservationService;
import com.community.residence.common.reservation.vo.ReservationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 资源预约管理控制器：预约状态机 + 可预约时段 + 违约处置（接口设计.md 9.7） */
@Tag(name = "资源预约管理", description = "公共资源预约与违约处置接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResidentReservationController {

    private final ReservationService reservationService;

    @Operation(summary = "创建预约", description = "时段须落在资源可预约模板内，冲突检测；仅可约今天起 7 天内，过去日期/已结束时段拒绝")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/resource-reservations")
    public ApiResponse<ReservationVO> create(@RequestBody @Valid CreateReservationDTO dto) {
        return ApiResponse.success(reservationService.create(dto));
    }

    @Operation(summary = "取消预约", description = "待审核/已预约 → 已取消；仅预约人本人")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/resource-reservations/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        reservationService.cancel(id, dto.getReason());
        return ApiResponse.success();
    }
}
