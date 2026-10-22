package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.PublicResourceService;
import com.community.residence.common.community.vo.ResourceVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公共资源管理控制器（接口设计.md 9.1.5） */
@Tag(name = "公共资源管理", description = "小区公共资源管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicResourceController {

    private final PublicResourceService resourceService;

    @Operation(summary = "查询资源详情")
    @GetMapping("/resources/{id}")
    public ApiResponse<ResourceVO> getById(@PathVariable Long id) {
        return ApiResponse.success(resourceService.getById(id));
    }

    @Operation(summary = "小区资源列表（分页）")
    @GetMapping("/communities/{communityId}/resources")
    public ApiResponse<PageVO<ResourceVO>> pageByCommunity(@PathVariable Long communityId,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) String status) {
        return ApiResponse.success(resourceService.pageByCommunity(communityId, page, size, type, status));
    }
}
