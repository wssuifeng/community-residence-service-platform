package com.community.residence.agreement.controller;

import com.community.residence.agreement.dto.AgreementConfirmDTO;
import com.community.residence.agreement.dto.CreateAgreementDTO;
import com.community.residence.agreement.service.AgreementService;
import com.community.residence.agreement.vo.LeaseAgreementVO;
import com.community.residence.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "发起租约协议", description = "管理方按模板生成协议正文快照并送居民确认；同租约存在未完成协议时拒绝")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<LeaseAgreementVO> create(@RequestBody @Valid CreateAgreementDTO dto) {
        return ApiResponse.success(agreementService.createAgreement(dto));
    }

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

    @Operation(summary = "确认协议", description = "按当前身份落居民方或管理方，双方均确认后协议生效并同步租约签约状态")
    @PreAuthorize("hasAnyRole('RESIDENT', 'ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/confirm")
    public ApiResponse<LeaseAgreementVO> confirm(@PathVariable Long id,
                                                 @RequestBody @Valid AgreementConfirmDTO dto) {
        return ApiResponse.success(agreementService.confirm(id, dto));
    }

    @Operation(summary = "撤回协议", description = "仅管理方；仅待确认/单方已确认状态可撤回，租约签约状态回落为未发起")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestParam String reason) {
        agreementService.cancel(id, reason);
        return ApiResponse.success();
    }
}
