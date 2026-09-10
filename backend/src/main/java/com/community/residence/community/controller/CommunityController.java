package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateCommunityDTO;
import com.community.residence.community.dto.UpdateCommunityStatusDTO;
import com.community.residence.community.service.CommunityService;
import com.community.residence.community.vo.CommunityVO;
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

/** 社区管理控制器：社区 CRUD 与停用/启用（接口设计.md 9.1.1） */
@Tag(name = "社区管理", description = "社区基础信息管理接口")
@RestController
@RequestMapping("/api/v1/communities")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @Operation(summary = "创建社区", description = "仅超级管理员可创建社区")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<CommunityVO> create(@RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.create(dto));
    }

    @Operation(summary = "更新社区", description = "超级管理员全局；社区管理员限绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<CommunityVO> update(@PathVariable Long id, @RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.update(id, dto));
    }

    @Operation(summary = "查询社区详情")
    @GetMapping("/{id}")
    public ApiResponse<CommunityVO> getById(@PathVariable Long id) {
        return ApiResponse.success(communityService.getById(id));
    }

    @Operation(summary = "社区列表（分页）")
    @GetMapping
    public ApiResponse<PageVO<CommunityVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.success(communityService.page(page, size, status, keyword));
    }

    @Operation(summary = "更新社区状态", description = "停用/启用社区，仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateCommunityStatusDTO dto) {
        communityService.updateStatus(id, dto.getStatus());
        return ApiResponse.success();
    }

    @Operation(summary = "删除社区（级联）", description = "仅超级管理员；社区退场时其下级结构与关联业务数据一并删除（R1/R6 v1.1），前端须二次确认")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        communityService.delete(id);
        return ApiResponse.success();
    }
}
