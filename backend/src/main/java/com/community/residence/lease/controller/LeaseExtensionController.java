package com.community.residence.lease.controller;
import com.community.residence.common.result.ApiResponse;
import com.community.residence.lease.dto.*;
import com.community.residence.lease.entity.LeaseExtensionApplication;
import com.community.residence.lease.service.LeaseExtensionService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
/** 租期延长独立于缴租，沿用租约管辖权限。 */
@RestController @RequestMapping("/api/v1/leases/{leaseId}/extensions") @RequiredArgsConstructor @Tag(name="租期延长")
public class LeaseExtensionController {
 private final LeaseExtensionService service;
 @GetMapping @PreAuthorize("hasAnyRole('RESIDENT','ADMIN','SUPER_ADMIN')") @Operation(summary="租期延长申请记录")
 public ApiResponse<List<LeaseExtensionApplication>> list(@PathVariable Long leaseId){return ApiResponse.success(service.list(leaseId));}
 @PostMapping @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="居民申请延长租期")
 public ApiResponse<LeaseExtensionApplication> apply(@PathVariable Long leaseId,@RequestBody @Valid LeaseExtensionDTO dto){return ApiResponse.success(service.apply(leaseId,dto));}
 @PostMapping("/{id}/withdraw") @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="撤回待审延长申请")
 public ApiResponse<Void> withdraw(@PathVariable Long leaseId,@PathVariable Long id){service.withdraw(leaseId,id);return ApiResponse.success();}
 @PostMapping("/{id}/review") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") @Operation(summary="批准或驳回租期延长")
 public ApiResponse<LeaseExtensionApplication> review(@PathVariable Long leaseId,@PathVariable Long id,@RequestBody @Valid ReviewLeaseExtensionDTO dto){return ApiResponse.success(service.review(leaseId,id,dto));}
}
