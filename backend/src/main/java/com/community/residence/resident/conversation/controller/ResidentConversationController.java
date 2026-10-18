package com.community.residence.resident.conversation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.common.housing.service.ViewingAppointmentService;
import com.community.residence.common.housing.vo.ViewingAppointmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 多方会话控制器：看房预约群聊 + 居民-社区管理员直通（接口设计.md 9.14，R63） */
@Tag(name = "多方会话", description = "看房预约群聊与居民-社区管理员直通会话接口")
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ResidentConversationController {
    private final com.community.residence.common.housing.service.HousingService housingService;
    private final ViewingAppointmentService viewingAppointmentService;

    /**
     * 会话内发起看房预约（R69，接口设计.md 9.19）：仅注册并登录的居民租约群参与者可发起；
     * 复用既有创建逻辑（同表/同状态机/同冲突校验），lease_id 取自会话 related_id，
     * **不再新建 VIEWING_GROUP**（直接复用该租约群作为会话载体）。
     */
    @Operation(summary = "会话内发起看房预约",
            description = "仅居民租约群聊参与者可在会话内预约看房；lease_id 取自会话关联租约，不再新建看房群聊")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/viewing-appointments")
    public ApiResponse<ViewingAppointmentVO> createViewingAppointment(
            @PathVariable Long id,
            @RequestBody @Valid CreateViewingAppointmentDTO dto) {
        return ApiResponse.success(viewingAppointmentService.createInConversation(id, dto));
    }

    @Operation(summary="租约会话来源房源预约上下文",description="仅承租居民且为未关闭租约会话参与者，不要求公开可见")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/{id}/viewing-context")
    public ApiResponse<com.community.residence.common.housing.vo.HousingVO> viewingContext(@PathVariable Long id) {
        return ApiResponse.success(housingService.privateLeaseViewingDetail(viewingAppointmentService.requireConversationViewingHousing(id,null)));
    }

    @Operation(summary="租约会话可预约时段",description="复用公开入口相同的时段与冲突计算，私有上下文不扩大公众范围")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/{id}/viewing-slots")
    public ApiResponse<java.util.List<com.community.residence.common.reservation.vo.AvailableSlotVO>> viewingSlots(@PathVariable Long id,
        @org.springframework.web.bind.annotation.RequestParam @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate startDate,
        @org.springframework.web.bind.annotation.RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate endDate) {
        return ApiResponse.success(housingService.availableSlotsForLeaseViewing(viewingAppointmentService.requireConversationViewingHousing(id,null),startDate,endDate));
    }
}
