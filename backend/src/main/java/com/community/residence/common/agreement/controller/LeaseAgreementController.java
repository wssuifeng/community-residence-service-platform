package com.community.residence.common.agreement.controller;

import com.community.residence.common.agreement.service.AgreementService;
import com.community.residence.common.agreement.vo.LeaseAgreementVO;
import com.community.residence.common.web.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 租约协议控制器（接口设计.md 9.3.3，R64）：管理方发起、双方确认、撤回 */
@Tag(name = "租赁协议", description = "租约协议轻量在线确认：发起、查看、双方确认、撤回")
@RestController
@RequestMapping("/api/v1/lease-agreements")
@RequiredArgsConstructor
public class LeaseAgreementController {

    private final AgreementService agreementService;

    @Operation(summary = "租约协议流水", description = "按租约查协议历史（含已撤回），居民限本人租约")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<List<LeaseAgreementVO>> listByLease(@RequestParam Long leaseId) {
        return ApiResponse.success(agreementService.listByLease(leaseId));
    }

    @Operation(summary = "协议详情", description = "居民限本人协议")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<LeaseAgreementVO> getById(@PathVariable Long id) {
        return ApiResponse.success(agreementService.getById(id));
    }
}
