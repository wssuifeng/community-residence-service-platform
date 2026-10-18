package com.community.residence.admin.lease.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.lease.dto.ApproveMoveOutDTO;
import com.community.residence.common.lease.dto.RejectMoveOutDTO;
import com.community.residence.common.lease.service.LeaseMoveOutApplicationService;
import com.community.residence.common.lease.vo.LeaseMoveOutApplicationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * 居民自助退租申请控制器（接口设计.md 9.19，R70）。
 * 创建端点挂在租约资源路径下（契约口径 POST /api/v1/leases/{leaseId}/move-out-applications），
 * 以绝对路径映射避免与 LeaseController 的 /api/v1/leases 基础路径耦合；
 * 审核与查询端点走 /api/v1/lease-move-out-applications。
 */
@Tag(name = "退租申请", description = "居民自助退租申请与审核（R70）")
@RestController
@RequiredArgsConstructor
public class AdminLeaseMoveOutApplicationController {

    private final LeaseMoveOutApplicationService moveOutApplicationService;

    @Operation(summary = "退租申请列表（分页）",
            description = "ADMIN/SUPER_ADMIN，按社区数据权限过滤；status 可选 PENDING/APPROVED/REJECTED")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/api/v1/lease-move-out-applications")
    public ApiResponse<PageVO<LeaseMoveOutApplicationVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long communityId) {
        return ApiResponse.success(moveOutApplicationService.page(page, size, status, communityId));
    }

    @Operation(summary = "审核通过退租申请",
            description = "租约转已搬出 + 居住关系迁出 + 房屋置空置 + 房源状态回收（默认 AVAILABLE）"
                    + " + 变更留痕 + 关闭租约群聊 + 通知居民")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/api/v1/lease-move-out-applications/{id}/approve")
    public ApiResponse<LeaseMoveOutApplicationVO> approve(@PathVariable Long id,
                                                          @RequestBody @Valid ApproveMoveOutDTO dto) {
        return ApiResponse.success(moveOutApplicationService.approve(id, dto));
    }

    @Operation(summary = "驳回退租申请", description = "理由必填；租约保持已生效，申请落已驳回终态")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/api/v1/lease-move-out-applications/{id}/reject")
    public ApiResponse<LeaseMoveOutApplicationVO> reject(@PathVariable Long id,
                                                         @RequestBody @Valid RejectMoveOutDTO dto) {
        return ApiResponse.success(moveOutApplicationService.reject(id, dto));
    }
}
