package com.community.residence.resident.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.resident.dto.CreateApplicationDTO;
import com.community.residence.common.resident.service.ResidenceApplicationService;
import com.community.residence.common.resident.vo.ApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 入住申请管理控制器（接口设计.md 9.2.2） */
@Tag(name = "入住申请管理", description = "入住申请提交与审核接口")
@RestController
@RequestMapping("/api/v1/residence-applications")
@RequiredArgsConstructor
public class ResidentResidenceApplicationController {

    private final ResidenceApplicationService applicationService;

    @Operation(summary = "提交入住申请", description = "房屋须空置；同一房屋不可重复在审")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<ApplicationVO> create(@RequestBody @Valid CreateApplicationDTO dto) {
        return ApiResponse.success(applicationService.create(dto));
    }

    @Operation(summary = "撤回入住申请", description = "仅申请人本人、仅待审核可撤回；落已撤回终态并关闭租约群聊")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/{id}/withdraw")
    public ApiResponse<ApplicationVO> withdraw(@PathVariable Long id) {
        return ApiResponse.success(applicationService.withdraw(id));
    }
}
