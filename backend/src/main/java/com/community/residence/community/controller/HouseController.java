package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.dto.UpdateHouseStatusDTO;
import com.community.residence.community.service.HouseService;
import com.community.residence.community.vo.HouseStatusHistoryVO;
import com.community.residence.community.vo.HouseVO;
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

/** 房屋管理控制器（接口设计.md 9.1.4） */
@Tag(name = "房屋管理", description = "单元房屋信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HouseController {

    private final HouseService houseService;

    @Operation(summary = "创建房屋")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/houses")
    public ApiResponse<HouseVO> create(@RequestBody @Valid CreateHouseDTO dto) {
        return ApiResponse.success(houseService.create(dto));
    }

    @Operation(summary = "更新房屋")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/houses/{id}")
    public ApiResponse<HouseVO> update(@PathVariable Long id, @RequestBody @Valid CreateHouseDTO dto) {
        return ApiResponse.success(houseService.update(id, dto));
    }

    @Operation(summary = "删除房屋", description = "房屋有在住居民时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/houses/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        houseService.delete(id);
        return ApiResponse.success();
    }

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

    @Operation(summary = "更新房屋状态", description = "状态变更记录写入历史表")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/houses/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateHouseStatusDTO dto) {
        houseService.updateStatus(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "房屋状态变更历史（分页）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/houses/{id}/status-history")
    public ApiResponse<PageVO<HouseStatusHistoryVO>> statusHistory(@PathVariable Long id,
                                                                   @RequestParam(defaultValue = "1") long page,
                                                                   @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(houseService.statusHistory(id, page, size));
    }
}
