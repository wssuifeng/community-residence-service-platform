package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.dto.BatchCreateHousesDTO;
import com.community.residence.common.community.dto.CreateHouseDTO;
import com.community.residence.common.community.dto.UpdateHouseStatusDTO;
import com.community.residence.common.community.service.HouseService;
import com.community.residence.common.community.vo.BatchCreateResultVO;
import com.community.residence.common.community.vo.HouseStatusHistoryVO;
import com.community.residence.common.community.vo.HouseVO;
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
    private final com.community.residence.common.community.service.HouseLayoutService houseLayoutService;

    private final com.community.residence.common.community.service.HouseAttributesService houseAttributesService;

    @Operation(summary = "原子编辑所选房屋自身属性", description = "不修改结构归属、状态及居住关系；任一非法项全部回滚")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/houses/attributes")
    public ApiResponse<Integer> updateAttributes(@RequestBody @Valid com.community.residence.common.community.dto.BatchHouseAttributesDTO dto) {
        return ApiResponse.success(houseAttributesService.update(dto));
    }

    @Operation(summary = "创建房屋")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/houses")
    public ApiResponse<HouseVO> create(@RequestBody @Valid CreateHouseDTO dto) {
        return ApiResponse.success(houseService.create(dto));
    }

    @Operation(summary = "批量创建房屋", description = "部分成功语义：逐行反馈（D-端点2）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/houses/batch")
    public ApiResponse<BatchCreateResultVO> batchCreate(@RequestBody @Valid BatchCreateHousesDTO dto) {
        return ApiResponse.success(houseService.batchCreate(dto));
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
    @Operation(summary="原子配置所选房屋户型", description="仅修改明确选区，同步关联挂牌；任何非法项整体拒绝")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/houses/layout")
    public ApiResponse<Integer> updateLayout(@RequestBody @Valid com.community.residence.common.community.dto.BatchHouseLayoutDTO dto) {
        return ApiResponse.success(houseLayoutService.update(dto));
    }
}
