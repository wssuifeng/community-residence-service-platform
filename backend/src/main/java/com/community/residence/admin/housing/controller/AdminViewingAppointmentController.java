package com.community.residence.admin.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.dto.AssignViewingDTO;
import com.community.residence.common.housing.service.ViewingAppointmentService;
import com.community.residence.common.housing.vo.AssigneeOptionVO;
import com.community.residence.common.housing.vo.ViewingAppointmentVO;
import com.community.residence.common.reservation.dto.ReservationActionDTO;
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

import java.util.List;

/** 看房预约管理控制器：居民预约 + 管理端处置（接口设计.md 9.12.2） */
@Tag(name = "看房预约管理", description = "看房预约提交与处置接口")
@RestController
@RequestMapping("/api/v1/viewing-appointments")
@RequiredArgsConstructor
public class AdminViewingAppointmentController {

    private final ViewingAppointmentService appointmentService;

    @Operation(summary = "分配带看人",
            description = "R59：将预约分配给启用状态的服务人员/小区管理员（禁超管），重复分配覆盖；成功后双方收到通知")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/assign")
    public ApiResponse<ViewingAppointmentVO> assign(@PathVariable Long id,
                                                    @RequestBody @Valid AssignViewingDTO dto) {
        return ApiResponse.success(appointmentService.assign(id, dto.getAssigneeId()));
    }

    @Operation(summary = "带看人候选列表",
            description = "R59：启用状态服务人员全量 + 管辖该小区的启用小区管理员；ADMIN 仅可查其绑定小区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/assignable-assignees")
    public ApiResponse<List<AssigneeOptionVO>> assignableAssignees(@RequestParam Long communityId) {
        return ApiResponse.success(appointmentService.assignableAssignees(communityId));
    }

    @Operation(summary = "确认预约", description = "待确认 → 已预约")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/confirm")
    public ApiResponse<Void> confirm(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.confirm(id, dto.getReason());
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
