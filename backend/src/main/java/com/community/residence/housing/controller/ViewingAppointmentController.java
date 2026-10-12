package com.community.residence.housing.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.housing.dto.AssignViewingDTO;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.dto.CreateViewingMessageDTO;
import com.community.residence.housing.service.ViewingAppointmentService;
import com.community.residence.housing.vo.AssigneeOptionVO;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.housing.vo.ViewingMessageVO;
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
import java.util.List;

/** 看房预约管理控制器：居民预约 + 管理端处置（接口设计.md 9.12.2） */
@Tag(name = "看房预约管理", description = "看房预约提交与处置接口")
@RestController
@RequestMapping("/api/v1/viewing-appointments")
@RequiredArgsConstructor
public class ViewingAppointmentController {

    private final ViewingAppointmentService appointmentService;

    @Operation(summary = "创建看房预约", description = "仅注册并登录的居民账号可提交；服务端从令牌绑定预约人身份")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<ViewingAppointmentVO> create(@RequestBody @Valid CreateViewingAppointmentDTO dto) {
        return ApiResponse.success(appointmentService.create(dto));
    }

    @Operation(summary = "查询预约详情", description = "居民仅可查看本人；服务人员仅可查看被分配预约；管理端按社区范围查看")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<ViewingAppointmentVO> getById(@PathVariable Long id) {
        return ApiResponse.success(appointmentService.getById(id));
    }

    @Operation(summary = "分配带看人",
            description = "R59：将预约分配给启用状态的服务人员/社区管理员（禁超管），重复分配覆盖；成功后双方收到通知")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/assign")
    public ApiResponse<ViewingAppointmentVO> assign(@PathVariable Long id,
                                                    @RequestBody @Valid AssignViewingDTO dto) {
        return ApiResponse.success(appointmentService.assign(id, dto.getAssigneeId()));
    }

    @Operation(summary = "带看人候选列表",
            description = "R59：启用状态服务人员全量 + 管辖该社区的启用社区管理员；ADMIN 仅可查其绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/assignable-assignees")
    public ApiResponse<List<AssigneeOptionVO>> assignableAssignees(@RequestParam Long communityId) {
        return ApiResponse.success(appointmentService.assignableAssignees(communityId));
    }

    @Operation(summary = "会话消息列表（R59 旧口径，保留兼容）",
            description = "已被 R63 多方会话取代（GET /conversations/{conversationId}/messages）；"
                    + "R59：预约居民/带看人/社区管理员可读，时间正序非分页；非参与者 403")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/messages")
    public ApiResponse<List<ViewingMessageVO>> messages(@PathVariable Long id) {
        return ApiResponse.success(appointmentService.listMessages(id));
    }

    @Operation(summary = "发送会话消息（R59 旧口径，保留兼容）",
            description = "已被 R63 多方会话取代（POST /conversations/{conversationId}/messages）；"
                    + "R59：仅预约居民或带看人可发送；WS 实时推送 /topic/appointment/{id} + HTTP 轮询兜底")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/messages")
    public ApiResponse<ViewingMessageVO> sendMessage(@PathVariable Long id,
                                                     @RequestBody @Valid CreateViewingMessageDTO dto) {
        return ApiResponse.success(appointmentService.sendMessage(id, dto));
    }

    @Operation(summary = "预约列表（分页）",
            description = "居民只看本人预约；服务人员看被分配的预约（我的带看，R59）；管理端按社区或全局范围查询，游客无预约列表权限")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
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

    @Operation(summary = "完成预约", description = "已预约 → 已完成；STAFF仅本人被分配预约，管理员按社区范围")
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/complete")
    public ApiResponse<Void> complete(@PathVariable Long id, @RequestBody @Valid ReservationActionDTO dto) {
        appointmentService.complete(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "取消预约", description = "居民本人或管理端取消；服务人员与游客无权取消")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
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
