package com.community.residence.common.notice.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.notice.service.NoticeService;
import com.community.residence.common.notice.vo.NoticeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** 公告管理控制器：草稿/发布/撤回状态机 + 查看回执（接口设计.md 9.5.1） */
@Tag(name = "公告管理", description = "公告广播管理接口")
@RestController
@RequestMapping("/api/v1/notices")
@RequiredArgsConstructor
public class NoticeController {

    private final NoticeService noticeService;

    @Operation(summary = "查询公告详情", description = "公开；仅已发布且未过期")
    @GetMapping("/{id}")
    public ApiResponse<NoticeVO> getById(@PathVariable Long id) {
        return ApiResponse.success(noticeService.getById(id));
    }

    @Operation(summary = "公告列表（分页）", description = "公开；管理端角色可见全部状态；支持 priority/isPinned/type 过滤（DEF-031/V12）")
    @GetMapping
    public ApiResponse<PageVO<NoticeVO>> page(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) Long communityId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String priority,
                                              @RequestParam(required = false) Integer isPinned,
                                              @RequestParam(required = false) String type) {
        return ApiResponse.success(noticeService.page(page, size, communityId, keyword,
                priority, isPinned, type));
    }
}
