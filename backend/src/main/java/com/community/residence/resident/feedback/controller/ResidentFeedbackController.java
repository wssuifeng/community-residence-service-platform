package com.community.residence.resident.feedback.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.feedback.dto.CreateFeedbackDTO;
import com.community.residence.common.feedback.service.FeedbackService;
import com.community.residence.common.feedback.vo.FeedbackVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 反馈管理控制器：反馈单 + 会话消息（P1 HTTP 轮询，接口设计.md 9.6） */
@Tag(name = "反馈管理", description = "居民反馈与会话接口")
@RestController
@RequestMapping("/api/v1/feedbacks")
@RequiredArgsConstructor
public class ResidentFeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "提交反馈", description = "居民端；初始待受理")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<FeedbackVO> create(@RequestBody @Valid CreateFeedbackDTO dto) {
        return ApiResponse.success(feedbackService.create(dto));
    }
}
