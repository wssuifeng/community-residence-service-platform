package com.community.residence.admin.feedback.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.feedback.dto.CloseFeedbackDTO;
import com.community.residence.common.feedback.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 反馈管理控制器：反馈单 + 会话消息（P1 HTTP 轮询，接口设计.md 9.6） */
@Tag(name = "反馈管理", description = "居民反馈与会话接口")
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class AdminFeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "办结反馈", description = "会话中 → 已办结；办结说明落档")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable Long id, @RequestBody @Valid CloseFeedbackDTO dto) {
        feedbackService.close(id, dto);
        return ApiResponse.success();
    }
}
