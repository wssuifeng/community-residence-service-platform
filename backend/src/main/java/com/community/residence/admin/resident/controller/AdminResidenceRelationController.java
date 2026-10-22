package com.community.residence.admin.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.resident.dto.MoveOutDTO;
import com.community.residence.common.resident.service.ResidenceRelationService;
import com.community.residence.common.resident.vo.RelationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 居住关系管理控制器：台账查询与迁出登记（接口设计.md 9.2.3） */
@Tag(name = "居住关系管理", description = "居民-房屋居住关系台账接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdminResidenceRelationController {

    private final ResidenceRelationService relationService;

    @Operation(summary = "房屋居民列表")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/houses/{houseId}/residents")
    public ApiResponse<PageVO<RelationVO>> pageByHouse(@PathVariable Long houseId,
                                                       @RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size,
                                                       @RequestParam(required = false) String status) {
        return ApiResponse.success(relationService.pageByHouse(houseId, page, size, status));
    }

    @Operation(summary = "管理员直建居住关系", description = "限绑定小区；OWNER/TENANT仅空置房直录；FAMILY/CO_RESIDENT依赖已入住有效主关系，不新增租约")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/residence-relations")
    public ApiResponse<RelationVO> adminCreate(@RequestBody @Valid
                                               com.community.residence.common.resident.dto.AdminCreateRelationDTO dto) {
        return ApiResponse.success(relationService.adminCreate(dto));
    }

    @Operation(summary = "办理迁出", description = "联动终止在住租约；房屋无在住居民时回翻空置")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/residence-relations/{id}/move-out")
    public ApiResponse<Void> moveOut(@PathVariable Long id, @RequestBody @Valid MoveOutDTO dto) {
        relationService.moveOut(id, dto);
        return ApiResponse.success();
    }
    @Operation(summary="查看主居住关系的在住同住人员")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @GetMapping("/residence-relations/{id}/dependents")
    public ApiResponse<java.util.List<RelationVO>> dependents(@PathVariable Long id) {
        return ApiResponse.success(relationService.dependents(id));
    }
}
