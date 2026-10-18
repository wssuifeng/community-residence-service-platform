package com.community.residence.resident.lease.controller;
import com.community.residence.common.lease.entity.LeaseContractTerm;
import com.community.residence.common.web.result.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
/** 合同段与续租确认入口；居民本人及管辖管理方按动作分别授权。 */
@RestController @RequestMapping("/api/v1/leases/{leaseId}/terms") @RequiredArgsConstructor @Tag(name="租约合同段")
public class ResidentLeaseContractTermController {
    @PostMapping("/{termId}/confirm") @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="居民同意续租协议，未付款不延长")
    public ApiResponse<LeaseContractTerm> confirm(@PathVariable Long leaseId,@PathVariable Long termId){
        throw new com.community.residence.common.web.exception.BusinessException(com.community.residence.common.web.constant.ErrorCode.INVALID_PARAM,"旧续租确认入口已停用，请取消未生效旧方案后申请延长租期；已有有效订单仍可继续");
    }
}
