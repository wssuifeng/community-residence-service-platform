package com.community.residence.admin.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.resident.dto.ApproveApplicationDTO;
import com.community.residence.common.resident.dto.RejectApplicationDTO;
import com.community.residence.common.resident.service.ResidenceApplicationService;
import com.community.residence.common.resident.vo.ApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 入住申请管理控制器（接口设计.md 9.2.2） */
@Tag(name = "入住申请管理", description = "入住申请提交与审核接口")
@RestController
@RequestMapping("/api/v1/residence-applications")
@RequiredArgsConstructor
public class AdminResidenceApplicationController {

    private final ResidenceApplicationService applicationService;

    @Operation(summary = "审批通过",
            description = "租客仅建立待签约租约及群聊；正式关系与房屋入住在协议完成且首期成功后建立")
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
