package com.community.residence.common.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.workorder.service.ServiceCategoryService;
import com.community.residence.common.workorder.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 服务类别管理控制器（接口设计.md 9.4.1） */
@Tag(name = "服务类别管理", description = "服务类别树维护接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ServiceCategoryController {

    private final ServiceCategoryService categoryService;

    @Operation(summary = "查询类别详情")
    @GetMapping("/service-categories/{id}")
    public ApiResponse<CategoryVO> getById(@PathVariable Long id) {
        return ApiResponse.success(categoryService.getById(id));
    }

    @Operation(summary = "社区服务类别树", description = "公开；居民提交工单时选择；已过滤停用类别（R17/DEF-041）")
    @GetMapping("/communities/{communityId}/service-categories")
    public ApiResponse<List<CategoryVO>> tree(@PathVariable Long communityId) {
        return ApiResponse.success(categoryService.treeByCommunity(communityId));
    }
}
