package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateUnitDTO;
import com.community.residence.community.service.UnitService;
import com.community.residence.community.vo.UnitVO;
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

/** 单元管理控制器（接口设计.md 9.1.3） */
@Tag(name = "单元管理", description = "楼栋单元信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UnitController {

    private final UnitService unitService;

    @Operation(summary = "创建单元")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/units")
    public ApiResponse<UnitVO> create(@RequestBody @Valid CreateUnitDTO dto) {
        return ApiResponse.success(unitService.create(dto));
    }

    @Operation(summary = "更新单元")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/units/{id}")
    public ApiResponse<UnitVO> update(@PathVariable Long id, @RequestBody @Valid CreateUnitDTO dto) {
        return ApiResponse.success(unitService.update(id, dto));
    }

    @Operation(summary = "删除单元", description = "单元下有房屋时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/units/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        unitService.delete(id);
        return ApiResponse.success();
    }

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
