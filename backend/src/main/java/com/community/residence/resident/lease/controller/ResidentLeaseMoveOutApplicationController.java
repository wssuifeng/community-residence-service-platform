package com.community.residence.resident.lease.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.lease.dto.CreateMoveOutApplicationDTO;
import com.community.residence.common.lease.service.LeaseMoveOutApplicationService;
import com.community.residence.common.lease.vo.LeaseMoveOutApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 居民自助退租申请控制器（接口设计.md 9.19，R70）。
 * 创建端点挂在租约资源路径下（契约口径 POST /api/v1/leases/{leaseId}/move-out-applications），
 * 以绝对路径映射避免与 LeaseController 的 /api/v1/leases 基础路径耦合；
 * 审核与查询端点走 /api/v1/lease-move-out-applications。
 */
@Tag(name = "退租申请", description = "居民自助退租申请与审核（R70）")
@RestController
@RequiredArgsConstructor
public class ResidentLeaseMoveOutApplicationController {

    private final LeaseMoveOutApplicationService moveOutApplicationService;

    @Operation(summary = "提交退租申请", description = "居民本人、仅已生效租约；已有待审核申请时拒绝 5603")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/api/v1/leases/{leaseId}/move-out-applications")
    public ApiResponse<LeaseMoveOutApplicationVO> create(
            @PathVariable Long leaseId,
            @RequestBody @Valid CreateMoveOutApplicationDTO dto) {
        return ApiResponse.success(moveOutApplicationService.create(leaseId, dto));
    }

    @Operation(summary = "我的退租申请列表", description = "居民端查看本人退租申请进度（按申请时间倒序）")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/api/v1/lease-move-out-applications/mine")
    public ApiResponse<List<LeaseMoveOutApplicationVO>> mine() {
        return ApiResponse.success(moveOutApplicationService.listByResident());
    }
}
