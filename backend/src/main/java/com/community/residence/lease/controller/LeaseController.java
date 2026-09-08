package com.community.residence.lease.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.lease.dto.CreateLeaseDTO;
import com.community.residence.lease.dto.RenewLeaseDTO;
import com.community.residence.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.lease.service.LeaseService;
import com.community.residence.lease.vo.LeaseVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 租住管理控制器（接口设计.md 9.3.1） */
@Tag(name = "租住管理", description = "租住记录与状态流转接口")
@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leaseService;

    @Operation(summary = "创建租住记录", description = "管理员登记，初始待审核")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<LeaseVO> create(@RequestBody @Valid CreateLeaseDTO dto) {
        return ApiResponse.success(leaseService.create(dto));
    }

    @Operation(summary = "更新租住记录")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<LeaseVO> update(@PathVariable Long id, @RequestBody @Valid CreateLeaseDTO dto) {
        return ApiResponse.success(leaseService.update(id, dto));
    }

    @Operation(summary = "续租", description = "仅已生效租约；止期顺延，租金/押金更新，状态保持已生效")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/renew")
    public ApiResponse<LeaseVO> renew(@PathVariable Long id, @RequestBody @Valid RenewLeaseDTO dto) {
        return ApiResponse.success(leaseService.renew(id, dto));
    }

    @Operation(summary = "查询租住详情", description = "居民限本人")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<LeaseVO> getById(@PathVariable Long id) {
        return ApiResponse.success(leaseService.getById(id));
    }

    @Operation(summary = "租住记录列表（分页）", description = "居民只看本人记录")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<LeaseVO>> page(@RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Long residentId,
                                             @RequestParam(required = false) Long houseId) {
        return ApiResponse.success(leaseService.page(page, size, status, residentId, houseId));
    }

    @Operation(summary = "更新租住状态", description = "状态机流转校验：待审核→已生效/已驳回等")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateLeaseStatusDTO dto) {
        leaseService.updateStatus(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "即将到期租住列表", description = "按到期日期升序")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/expiring")
    public ApiResponse<PageVO<LeaseVO>> expiring(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(leaseService.expiring(page, size, days));
    }
}
