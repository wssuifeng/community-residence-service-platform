package com.community.residence.common.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.resident.service.ResidenceRelationService;
import com.community.residence.common.resident.vo.RelationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 居住关系管理控制器：台账查询与迁出登记（接口设计.md 9.2.3） */
@Tag(name = "居住关系管理", description = "居民-房屋居住关系台账接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResidenceRelationController {

    private final ResidenceRelationService relationService;

    @Operation(summary = "居民居住关系列表", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/residents/{residentId}/residences")
    public ApiResponse<PageVO<RelationVO>> pageByResident(@PathVariable Long residentId,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "20") long size,
                                                          @RequestParam(required = false) String status) {
        return ApiResponse.success(relationService.pageByResident(residentId, page, size, status));
    }
}
