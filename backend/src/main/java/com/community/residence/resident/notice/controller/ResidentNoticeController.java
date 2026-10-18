package com.community.residence.resident.notice.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.notice.service.NoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** 公告管理控制器：草稿/发布/撤回状态机 + 查看回执（接口设计.md 9.5.1） */
@Tag(name = "公告管理", description = "公告广播管理接口")
@RestController
@RequestMapping("/api/v1/notices")
@RequiredArgsConstructor
public class ResidentNoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "记录公告查看", description = "同一用户同一公告仅记录一次")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/view")
    public ApiResponse<Void> recordView(@PathVariable Long id,@RequestParam(required=false)Integer version) {
        noticeService.recordView(id,version);
        return ApiResponse.success();
    }
}
