package com.community.residence.resident.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.resident.dto.ApproveApplicationDTO;
import com.community.residence.resident.dto.CreateApplicationDTO;
import com.community.residence.resident.dto.RejectApplicationDTO;
import com.community.residence.resident.service.ResidenceApplicationService;
import com.community.residence.resident.vo.ApplicationVO;
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

/** 入住申请管理控制器（接口设计.md 9.2.2） */
@Tag(name = "入住申请管理", description = "入住申请提交与审核接口")
@RestController
@RequestMapping("/api/v1/residence-applications")
@RequiredArgsConstructor
public class ResidenceApplicationController {

    private final ResidenceApplicationService applicationService;

    @Operation(summary = "提交入住申请", description = "房屋须空置；同一房屋不可重复在审")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<ApplicationVO> create(@RequestBody @Valid CreateApplicationDTO dto) {
        return ApiResponse.success(applicationService.create(dto));
    }

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

    @Operation(summary = "审批通过", description = "自动建立居住关系/租住记录并翻转房屋状态")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/approve")
    public ApiResponse<ApplicationVO> approve(@PathVariable Long id,
                                              @RequestBody @Valid ApproveApplicationDTO dto) {
        return ApiResponse.success(applicationService.approve(id, dto));
    }

    @Operation(summary = "审批拒绝")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable Long id, @RequestBody @Valid RejectApplicationDTO dto) {
        applicationService.reject(id, dto);
        return ApiResponse.success();
    }
}
