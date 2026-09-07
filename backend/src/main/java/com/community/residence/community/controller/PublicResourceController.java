package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateResourceDTO;
import com.community.residence.community.service.PublicResourceService;
import com.community.residence.community.vo.ResourceVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公共资源管理控制器（接口设计.md 9.1.5） */
@Tag(name = "公共资源管理", description = "社区公共资源管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PublicResourceController {

    private final PublicResourceService resourceService;

    @Operation(summary = "创建公共资源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/resources")
    public ApiResponse<ResourceVO> create(@RequestBody @Valid CreateResourceDTO dto) {
        return ApiResponse.success(resourceService.create(dto));
    }

    @Operation(summary = "更新公共资源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/resources/{id}")
    public ApiResponse<ResourceVO> update(@PathVariable Long id, @RequestBody @Valid CreateResourceDTO dto) {
        return ApiResponse.success(resourceService.update(id, dto));
    }

    @Operation(summary = "删除公共资源", description = "有未完成预约时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/resources/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        resourceService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "查询资源详情")
    @GetMapping("/resources/{id}")
    public ApiResponse<ResourceVO> getById(@PathVariable Long id) {
        return ApiResponse.success(resourceService.getById(id));
    }

    @Operation(summary = "社区资源列表（分页）")
    @GetMapping("/communities/{communityId}/resources")
    public ApiResponse<PageVO<ResourceVO>> pageByCommunity(@PathVariable Long communityId,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) String status) {
        return ApiResponse.success(resourceService.pageByCommunity(communityId, page, size, type, status));
    }
}
