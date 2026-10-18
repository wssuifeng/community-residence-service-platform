package com.community.residence.common.feedback.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.feedback.service.FeedbackService;
import com.community.residence.common.feedback.vo.AttachmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 反馈附件上传（接口设计.md 9.6.2.1）；删除端点在 FeedbackAttachmentController（路径前缀不同） */
@Tag(name = "反馈管理", description = "居民反馈与会话接口")
@RestController
@RequiredArgsConstructor
public class FeedbackAttachmentController {

    private final FeedbackService feedbackService;

    @Operation(summary = "上传反馈附件", description = "居民限反馈提交人；图片 ≤5MB，文档 ≤10MB")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/api/v1/feedbacks/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentVO> uploadAttachment(@PathVariable Long id,
                                                      @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(feedbackService.uploadAttachment(id, file));
    }

    @Operation(summary = "删除反馈附件", description = "居民限反馈提交人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/feedback-attachments/{id}")
    public ApiResponse<Void> deleteAttachment(@PathVariable Long id) {
        feedbackService.deleteAttachment(id);
        return ApiResponse.success();
    }
    @Operation(summary = "发送图片消息", description = "真实图片≤5MB；保存并向会话参与者发送，不是反馈附件")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/api/v1/feedbacks/{id}/messages/image", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<com.community.residence.common.feedback.vo.MessageVO> sendImage(@PathVariable Long id, @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(feedbackService.sendImage(id,file));
    }

}
