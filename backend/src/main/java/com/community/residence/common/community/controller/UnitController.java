package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.UnitService;
import com.community.residence.common.community.vo.UnitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 单元管理控制器（接口设计.md 9.1.3） */
@Tag(name = "单元管理", description = "楼栋单元信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @Operation(summary = "查询单元详情")
    @GetMapping("/units/{id}")
    public ApiResponse<UnitVO> getById(@PathVariable Long id) {
        return ApiResponse.success(unitService.getById(id));
    }

    @Operation(summary = "楼栋单元列表（分页）")
    @GetMapping("/buildings/{buildingId}/units")
    public ApiResponse<PageVO<UnitVO>> pageByBuilding(@PathVariable Long buildingId,
                                                      @RequestParam(defaultValue = "1") long page,
                                                      @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(unitService.pageByBuilding(buildingId, page, size));
    }
}
