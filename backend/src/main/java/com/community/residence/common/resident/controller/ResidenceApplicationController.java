package com.community.residence.common.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.resident.service.ResidenceApplicationService;
import com.community.residence.common.resident.vo.ApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 入住申请管理控制器（接口设计.md 9.2.2） */
@Tag(name = "入住申请管理", description = "入住申请提交与审核接口")
@RestController
@RequestMapping("/api/v1/residence-applications")
@RequiredArgsConstructor
public class ResidenceApplicationController {

    private final ResidenceApplicationService applicationService;

    @Operation(summary = "查询申请详情", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<ApplicationVO> getById(@PathVariable Long id) {
        return ApiResponse.success(applicationService.getById(id));
    }

    @Operation(summary = "申请列表（分页）", description = "居民只看本人申请")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<ApplicationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.success(applicationService.page(page, size, status));
    }
}
