package com.community.residence.admin.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.community.dto.BatchCreateUnitsDTO;
import com.community.residence.common.community.dto.CreateUnitDTO;
import com.community.residence.common.community.service.UnitService;
import com.community.residence.common.community.vo.BatchCreateResultVO;
import com.community.residence.common.community.vo.UnitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单元管理控制器（接口设计.md 9.1.3） */
@Tag(name = "单元管理", description = "楼栋单元信息管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminUnitController {

    private final UnitService unitService;

    @Operation(summary = "创建单元")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/units")
    public ApiResponse<UnitVO> create(@RequestBody @Valid CreateUnitDTO dto) {
        return ApiResponse.success(unitService.create(dto));
    }

    @Operation(summary = "批量创建单元", description = "按名称列表逐项插入，部分成功语义：逐行反馈（D-端点2）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/units/batch")
    public ApiResponse<BatchCreateResultVO> batchCreate(@RequestBody @Valid BatchCreateUnitsDTO dto) {
        return ApiResponse.success(unitService.batchCreate(dto));
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
}
