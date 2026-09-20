package com.community.residence.conversation.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.conversation.dto.MarkReadDTO;
import com.community.residence.conversation.dto.OpenDirectDTO;
import com.community.residence.conversation.dto.SendMessageDTO;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.conversation.vo.ConversationMessageVO;
import com.community.residence.conversation.vo.ConversationVO;
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

    @Operation(summary = "我的会话列表（分页）",
            description = "参与者视角，含未读数与最后消息摘要；type 过滤：VIEWING_GROUP/DIRECT")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<ConversationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) String type) {
        return ApiResponse.success(conversationService.page(page, size, type));
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
            description = "居民与本社区管理员直通（幂等：已有会话直接返回）；居民须属于该社区")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/direct")
    public ApiResponse<ConversationVO> openDirect(@RequestBody @Valid OpenDirectDTO dto) {
        return ApiResponse.success(conversationService.openDirect(dto.getCommunityId()));
    }

    @Operation(summary = "标记会话已读",
            description = "更新参与者已读游标（仅前移）；lastMessageId=最后已读消息ID")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id, @RequestBody @Valid MarkReadDTO dto) {
        conversationService.markRead(id, dto.getLastMessageId());
        return ApiResponse.success();
    }
}
