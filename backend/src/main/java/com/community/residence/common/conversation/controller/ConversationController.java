package com.community.residence.common.conversation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.conversation.dto.MarkReadDTO;
import com.community.residence.common.conversation.dto.OpenDirectDTO;
import com.community.residence.common.conversation.dto.SendMessageDTO;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.conversation.vo.ConversationMessageVO;
import com.community.residence.common.conversation.vo.ConversationVO;
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
public class ConversationController {

    private final ConversationService conversationService;
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

    @Operation(summary = "我的会话列表（分页）",
            description = "参与者视角，含未读数与最后消息摘要；type 过滤：VIEWING_GROUP/DIRECT")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<ConversationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String type) {
        return ApiResponse.success(conversationService.page(page, size, type));
    }

    @Operation(summary = "会话详情",
            description = "四角色通用，仅参与者可见（非参与者 403，会话不存在 404）；"
                    + "返回 status/type/relatedId 与参与者概要，供租约群聊业务入口与已关闭态展示")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<ConversationVO> getById(@PathVariable Long id) {
        return ApiResponse.success(conversationService.getById(id));
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

    @Operation(summary = "会话消息列表", description = "仅参与者可读，时间正序非分页；非参与者 403")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/messages")
    public ApiResponse<List<ConversationMessageVO>> messages(@PathVariable Long id) {
        return ApiResponse.success(conversationService.listMessages(id));
    }

    @Operation(summary = "发送会话消息",
            description = "仅参与者可发送；WS 实时推送 /topic/conversation/{id} + HTTP 轮询兜底")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/messages")
    public ApiResponse<ConversationMessageVO> sendMessage(@PathVariable Long id,
                                                          @RequestBody @Valid SendMessageDTO dto) {
        return ApiResponse.success(conversationService.sendMessage(id, dto));
    }

    @Operation(summary = "创建/获取直通会话",
            description = "居民与本社区管理员直通（幂等：已有会话直接返回）；居民须属于该社区。"
                    + "DEF-076：管理端（ADMIN/SUPER_ADMIN）可代居民发起——须在请求体传 residentId，"
                    + "居民本人调用可省略（传他人一律 403）")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/direct")
    public ApiResponse<ConversationVO> openDirect(@RequestBody @Valid OpenDirectDTO dto) {
        return ApiResponse.success(
                conversationService.openDirect(dto.getCommunityId(), dto.getResidentId()));
    }

    @Operation(summary = "建/取租约沟通会话",
            description = "DEF-076 管理端补建入口（幂等）：审批通过内核已自动建群，本端点用于存量/旁路数据"
                    + "缺会话时补建，或在会话中心按 leaseId 直接进入；已存在则返回既有会话，"
                    + "并补写 lease_record.conversation_id（承约前置 DEF-077 的判定依据）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/lease-groups")
    public ApiResponse<ConversationVO> ensureLeaseGroup(
            @RequestBody @Valid com.community.residence.common.conversation.dto.EnsureLeaseGroupDTO dto) {
        return ApiResponse.success(conversationService.ensureLeaseGroup(dto.getLeaseId()));
    }

    @Operation(summary = "标记会话已读",
            description = "更新参与者已读游标（仅前移）；lastMessageId=最后已读消息ID")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, @RequestBody @Valid MarkReadDTO dto) {
        conversationService.markRead(id, dto.getLastMessageId());
        return ApiResponse.success();
    }
    @Operation(summary = "发送图片消息", description = "真实图片≤5MB；保存并向会话参与者发送，不是反馈附件")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/{id}/messages/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ConversationMessageVO> sendImage(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(conversationService.sendImage(id,file));
    }

}
