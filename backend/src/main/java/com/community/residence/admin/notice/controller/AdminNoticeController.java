package com.community.residence.admin.notice.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.notice.dto.CreateNoticeDTO;
import com.community.residence.common.notice.dto.PublishNoticeDTO;
import com.community.residence.common.notice.dto.WithdrawNoticeDTO;
import com.community.residence.common.notice.service.NoticeService;
import com.community.residence.common.notice.vo.NoticeVO;
import com.community.residence.common.notice.vo.NoticeViewRecordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/** 公告管理控制器：草稿/发布/撤回状态机 + 查看回执（接口设计.md 9.5.1） */
@Tag(name = "公告管理", description = "公告广播管理接口")
@RestController
@RequestMapping("/api/v1/notices")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "创建公告", description = "初始草稿状态；communityId 为空表示全系统广播（仅超管）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<NoticeVO> create(@RequestBody @Valid CreateNoticeDTO dto) {
        return ApiResponse.success(noticeService.create(dto));
    }

    @Operation(summary = "更新公告", description = "仅草稿状态可修改")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<NoticeVO> update(@PathVariable Long id, @RequestBody @Valid CreateNoticeDTO dto) {
        return ApiResponse.success(noticeService.update(id, dto));
    }

    @Operation(summary = "删除公告", description = "仅草稿/已撤回状态可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "发布公告", description = "草稿 → 已发布")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable Long id,
                                     @RequestBody @Valid PublishNoticeDTO dto) {
        noticeService.publish(id, LocalDateTime.parse(dto.getPublishTime()));
        return ApiResponse.success();
    }

    @Operation(summary = "撤回公告", description = "已发布 → 已撤回")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable Long id,
                                      @RequestBody @Valid WithdrawNoticeDTO dto) {
        noticeService.withdraw(id, dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "公告查看记录（分页）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/viewers")
    public ApiResponse<PageVO<NoticeViewRecordVO>> viewers(@PathVariable Long id,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(noticeService.viewers(id, page, size));
    }
}
