package com.community.residence.common.lease.controller;
import com.community.residence.common.lease.service.LeaseContractTermService;
import com.community.residence.common.web.result.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
/** 合同段与续租确认入口；居民本人及管辖管理方按动作分别授权。 */
@RestController @RequestMapping("/api/v1/leases/{leaseId}/terms") @RequiredArgsConstructor @Tag(name="租约合同段")
public class LeaseContractTermController {
    private final LeaseContractTermService service;
    @GetMapping @PreAuthorize("hasAnyRole('RESIDENT','ADMIN','SUPER_ADMIN')") @Operation(summary="合同及续租协议快照")
    public ApiResponse<List<com.community.residence.common.lease.vo.LeaseContractTermVO>> list(@PathVariable Long leaseId){
        return ApiResponse.success(service.list(leaseId));
    }
    @PostMapping("/{termId}/cancel") @PreAuthorize("hasAnyRole('RESIDENT','ADMIN','SUPER_ADMIN')") @Operation(summary="取消未生效续租，不改变原合同")
    public ApiResponse<Void> cancel(@PathVariable Long leaseId,@PathVariable Long termId){
        service.cancel(leaseId,termId);
        return ApiResponse.success();
    }
}
