package com.community.residence.common.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.housing.dto.CreateViewingMessageDTO;
import com.community.residence.common.housing.service.ViewingAppointmentService;
import com.community.residence.common.housing.vo.ViewingAppointmentVO;
import com.community.residence.common.housing.vo.ViewingMessageVO;
import com.community.residence.common.reservation.dto.ReservationActionDTO;
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

    @Operation(summary = "查询预约详情", description = "居民仅可查看本人；服务人员仅可查看被分配预约；管理端按社区范围查看")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<ViewingAppointmentVO> getById(@PathVariable Long id) {
        return ApiResponse.success(appointmentService.getById(id));
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
    @Operation(summary = "发送图片消息", description = "真实图片≤5MB；保存并向会话参与者发送，不是反馈附件")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/{id}/messages/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.community.residence.common.housing.vo.ViewingMessageVO> sendImage(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(appointmentService.sendImage(id,file));
    }
}
