package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.HouseService;
import com.community.residence.common.community.vo.HouseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 房屋管理控制器（接口设计.md 9.1.4） */
@Tag(name = "房屋管理", description = "单元房屋信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HouseController {

    private final HouseService houseService;

    @Operation(summary = "查询房屋详情")
    @GetMapping("/houses/{id}")
    public ApiResponse<HouseVO> getById(@PathVariable Long id) {
        return ApiResponse.success(houseService.getById(id));
    }

    @Operation(summary = "单元房屋列表（分页）")
    @GetMapping("/units/{unitId}/houses")
    public ApiResponse<PageVO<HouseVO>> pageByUnit(@PathVariable Long unitId,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.success(houseService.pageByUnit(unitId, page, size, status));
    }
}
