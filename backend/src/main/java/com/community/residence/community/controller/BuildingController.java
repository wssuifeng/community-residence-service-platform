package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateBuildingDTO;
import com.community.residence.community.service.BuildingService;
import com.community.residence.community.vo.BuildingVO;
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

/** 楼栋管理控制器（接口设计.md 9.1.2） */
@Tag(name = "楼栋管理", description = "社区楼栋信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;

    @Operation(summary = "创建楼栋")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/buildings")
    public ApiResponse<BuildingVO> create(@RequestBody @Valid CreateBuildingDTO dto) {
        return ApiResponse.success(buildingService.create(dto));
    }

    @Operation(summary = "更新楼栋")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/buildings/{id}")
    public ApiResponse<BuildingVO> update(@PathVariable Long id, @RequestBody @Valid CreateBuildingDTO dto) {
        return ApiResponse.success(buildingService.update(id, dto));
    }

    @Operation(summary = "删除楼栋", description = "楼栋下有单元时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/buildings/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        buildingService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "查询楼栋详情")
    @GetMapping("/buildings/{id}")
    public ApiResponse<BuildingVO> getById(@PathVariable Long id) {
        return ApiResponse.success(buildingService.getById(id));
    }

    @Operation(summary = "社区楼栋列表（分页）")
    @GetMapping("/communities/{communityId}/buildings")
    public ApiResponse<PageVO<BuildingVO>> pageByCommunity(@PathVariable Long communityId,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @RequestParam(required = false) String keyword) {
        return ApiResponse.success(buildingService.pageByCommunity(communityId, page, size, keyword));
    }
}
