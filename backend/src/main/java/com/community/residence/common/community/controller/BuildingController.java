package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.BuildingService;
import com.community.residence.common.community.vo.BuildingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 楼栋管理控制器（接口设计.md 9.1.2） */
@Tag(name = "楼栋管理", description = "小区楼栋信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BuildingController {

    private final BuildingService buildingService;

    @Operation(summary = "查询楼栋详情")
    @GetMapping("/buildings/{id}")
    public ApiResponse<BuildingVO> getById(@PathVariable Long id) {
        return ApiResponse.success(buildingService.getById(id));
    }

    @Operation(summary = "小区楼栋列表（分页）")
    @GetMapping("/communities/{communityId}/buildings")
    public ApiResponse<PageVO<BuildingVO>> pageByCommunity(@PathVariable Long communityId,
                                                           @RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @RequestParam(required = false) String keyword) {
        return ApiResponse.success(buildingService.pageByCommunity(communityId, page, size, keyword));
    }
}
