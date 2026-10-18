package com.community.residence.admin.conversation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.conversation.vo.ConversationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 多方会话控制器：看房预约群聊 + 居民-社区管理员直通（接口设计.md 9.14，R63） */
@Tag(name = "多方会话", description = "看房预约群聊与居民-社区管理员直通会话接口")
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class AdminConversationController {

    private final ConversationService conversationService;

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
}
