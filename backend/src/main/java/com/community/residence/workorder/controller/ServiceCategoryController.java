package com.community.residence.workorder.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.workorder.dto.CreateCategoryDTO;
import com.community.residence.workorder.service.ServiceCategoryService;
import com.community.residence.workorder.vo.CategoryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "创建服务类别", description = "支持二级分类")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/service-categories")
    public ApiResponse<CategoryVO> create(@RequestBody @Valid CreateCategoryDTO dto) {
        return ApiResponse.success(categoryService.create(dto));
    }

    @Operation(summary = "更新服务类别")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/service-categories/{id}")
    public ApiResponse<CategoryVO> update(@PathVariable Long id, @RequestBody @Valid CreateCategoryDTO dto) {
        return ApiResponse.success(categoryService.update(id, dto));
    }

    @Operation(summary = "删除服务类别", description = "有工单引用或子类别时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/service-categories/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "查询类别详情")
    @GetMapping("/service-categories/{id}")
    public ApiResponse<CategoryVO> getById(@PathVariable Long id) {
        return ApiResponse.success(categoryService.getById(id));
    }

    @Operation(summary = "社区服务类别树", description = "公开；居民提交工单时选择")
    @GetMapping("/communities/{communityId}/service-categories")
    public ApiResponse<List<CategoryVO>> tree(@PathVariable Long communityId) {
        return ApiResponse.success(categoryService.treeByCommunity(communityId));
    }
}
