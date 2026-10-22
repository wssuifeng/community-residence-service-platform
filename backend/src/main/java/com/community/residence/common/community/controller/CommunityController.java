package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.community.vo.CommunityVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** 小区管理控制器：小区 CRUD 与停用/启用（接口设计.md 9.1.1） */
@Tag(name = "小区管理", description = "小区基础信息管理接口")
@RestController
@RequestMapping("/api/v1/communities")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @Operation(summary = "查询小区详情")
    @GetMapping("/{id}")
    public ApiResponse<CommunityVO> getById(@PathVariable Long id) {
        return ApiResponse.success(communityService.getById(id));
    }

    @Operation(summary = "小区列表（分页）")
    @GetMapping
    public ApiResponse<PageVO<CommunityVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.success(communityService.page(page, size, status, keyword));
    }
}
