package com.community.residence.admin.agreement.controller;

import com.community.residence.common.agreement.dto.CreateAgreementDTO;
import com.community.residence.common.agreement.service.AgreementService;
import com.community.residence.common.agreement.vo.LeaseAgreementVO;
import com.community.residence.common.web.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** 租约协议控制器（接口设计.md 9.3.3，R64）：管理方发起、双方确认、撤回 */
@Tag(name = "租赁协议", description = "租约协议轻量在线确认：发起、查看、双方确认、撤回")
@RestController
@RequestMapping("/api/v1/lease-agreements")
@RequiredArgsConstructor
public class AdminLeaseAgreementController {

    private final AgreementService agreementService;

    @Operation(summary = "发起租约协议", description = "管理方同意并发出协议正文快照，记录管理方同意并待居民确认；同租约存在未完成协议时拒绝")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<LeaseAgreementVO> create(@RequestBody @Valid CreateAgreementDTO dto) {
        return ApiResponse.success(agreementService.createAgreement(dto));
    }

    @Operation(summary = "撤回协议", description = "仅管理方；仅待确认/单方已确认状态可撤回，租约签约状态回落为未发起")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long id, @RequestParam String reason) {
        agreementService.cancel(id, reason);
        return ApiResponse.success();
    }
}
