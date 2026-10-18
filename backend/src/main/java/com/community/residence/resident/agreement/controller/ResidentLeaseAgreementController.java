package com.community.residence.resident.agreement.controller;

import com.community.residence.common.agreement.dto.AgreementConfirmDTO;
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
import org.springframework.web.bind.annotation.RestController;


/** 租约协议控制器（接口设计.md 9.3.3，R64）：管理方发起、双方确认、撤回 */
@Tag(name = "租赁协议", description = "租约协议轻量在线确认：发起、查看、双方确认、撤回")
@RestController
@RequestMapping("/api/v1/lease-agreements")
@RequiredArgsConstructor
public class ResidentLeaseAgreementController {

    private final AgreementService agreementService;

    @Operation(summary = "确认协议", description = "仅居民本人同意当前正文快照；管理员发出时已同意，协议完成且首期成功后租约生效")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/{id}/confirm")
    public ApiResponse<LeaseAgreementVO> confirm(@PathVariable Long id,
                                                 @RequestBody @Valid AgreementConfirmDTO dto) {
        return ApiResponse.success(agreementService.confirm(id, dto));
    }
}
