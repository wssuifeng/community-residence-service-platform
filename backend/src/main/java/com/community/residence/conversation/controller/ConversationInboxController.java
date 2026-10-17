package com.community.residence.conversation.controller;
import com.community.residence.common.result.*;
import com.community.residence.conversation.dto.MarkReadDTO;
import com.community.residence.conversation.service.ConversationInboxService;
import com.community.residence.conversation.vo.ConversationInboxVO;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.community.residence.conversation.vo.ConversationMessageWindowVO;
/** 统一会话呈现接口，消息发送沿用原来源端点。 */
@RestController
@RequestMapping("/api/v1/conversation-inbox")
@RequiredArgsConstructor
@Tag(name="统一会话工作区")
@PreAuthorize("hasAnyRole('RESIDENT','STAFF','ADMIN','SUPER_ADMIN')")
public class ConversationInboxController {
 private final ConversationInboxService service;
 @Operation(summary="统一会话列表",description="原权限内服务端搜索、未读过滤及最后活动排序")
 @GetMapping
 public ApiResponse<PageVO<ConversationInboxVO>> page(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="30") int size,
 @RequestParam(required=false) String type,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean unreadOnly){return ApiResponse.success(service.page(page,size,type,keyword,unreadOnly));}
 @Operation(summary="来源消息历史窗口",description="无游标从首条未读或最近窗口开始，返回records/阅读定位/前后历史标识；beforeId/afterId互斥")
 @GetMapping("/{source}/{id}/messages")
 public ApiResponse<ConversationMessageWindowVO> messages(@PathVariable String source,@PathVariable Long id,@RequestParam(required=false) Long beforeId,@RequestParam(required=false) Long afterId,@RequestParam(defaultValue="40") int size){return ApiResponse.success(service.messages(source,id,beforeId,afterId,size));}
 @Operation(summary="确认反馈可见消息已读",description="0仅确认原始反馈，原子前移且不触发受理")
 @PostMapping("/FEEDBACK/{id}/read")
 @PreAuthorize("hasAnyRole('RESIDENT','ADMIN','SUPER_ADMIN')")
 public ApiResponse<Void> read(@PathVariable Long id,@RequestBody @Valid MarkReadDTO dto){service.markFeedbackRead(id,dto.getLastMessageId());return ApiResponse.success();}
}
