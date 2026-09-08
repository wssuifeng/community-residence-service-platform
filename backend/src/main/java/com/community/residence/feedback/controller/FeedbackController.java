package com.community.residence.feedback.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.feedback.dto.CloseFeedbackDTO;
import com.community.residence.feedback.dto.CreateFeedbackDTO;
import com.community.residence.feedback.dto.SendMessageDTO;
import com.community.residence.feedback.service.FeedbackService;
import com.community.residence.feedback.vo.AttachmentVO;
import com.community.residence.feedback.vo.FeedbackVO;
import com.community.residence.feedback.vo.MessageVO;
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

import java.time.LocalDateTime;
import java.util.List;

/** 反馈管理控制器：反馈单 + 会话消息（P1 HTTP 轮询，接口设计.md 9.6） */
@Tag(name = "反馈管理", description = "居民反馈与会话接口")
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "提交反馈", description = "居民端；初始待受理")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<FeedbackVO> create(@RequestBody @Valid CreateFeedbackDTO dto) {
        return ApiResponse.success(feedbackService.create(dto));
    }

    @Operation(summary = "查询反馈详情", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<FeedbackVO> getById(@PathVariable Long id) {
        return ApiResponse.success(feedbackService.getById(id));
    }

    @Operation(summary = "反馈列表（分页）", description = "居民只看本人反馈")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<FeedbackVO>> page(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String category) {
        return ApiResponse.success(feedbackService.page(page, size, status, category));
    }

    @Operation(summary = "办结反馈", description = "会话中 → 已办结；办结说明落档")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id, @RequestBody @Valid CloseFeedbackDTO dto) {
        feedbackService.close(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "发送会话消息", description = "管理员首次回复自动受理；办结后不可会话")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/messages")
    public ApiResponse<MessageVO> sendMessage(@PathVariable Long id, @RequestBody @Valid SendMessageDTO dto) {
        return ApiResponse.success(feedbackService.sendMessage(id, dto));
    }

    @Operation(summary = "反馈附件列表", description = "文件上传 P2 接入；当前返回空数组")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/attachments")
    public ApiResponse<List<AttachmentVO>> attachments(@PathVariable Long id) {
        return ApiResponse.success(feedbackService.attachments(id));
    }

    @Operation(summary = "会话消息列表", description = "支持 since 增量拉取（P1 轮询用）")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/messages")
    public ApiResponse<List<MessageVO>> messages(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        return ApiResponse.success(feedbackService.messages(id, since));
    }
}
