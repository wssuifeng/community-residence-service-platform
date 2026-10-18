package com.community.residence.resident.lease.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.lease.dto.LeaseExtensionDTO;
import com.community.residence.common.lease.entity.LeaseExtensionApplication;
import com.community.residence.common.lease.service.LeaseExtensionService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
/** 租期延长独立于缴租，沿用租约管辖权限。 */
@RestController @RequestMapping("/api/v1/leases/{leaseId}/extensions") @RequiredArgsConstructor @Tag(name="租期延长")
public class ResidentLeaseExtensionController {
 private final LeaseExtensionService service;
 @PostMapping @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="居民申请延长租期")
 public ApiResponse<LeaseExtensionApplication> apply(@PathVariable Long leaseId,@RequestBody @Valid LeaseExtensionDTO dto){return ApiResponse.success(service.apply(leaseId,dto));}
 @PostMapping("/{id}/withdraw") @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="撤回待审延长申请")
 public ApiResponse<Void> withdraw(@PathVariable Long leaseId,@PathVariable Long id){service.withdraw(leaseId,id);return ApiResponse.success();}
}
