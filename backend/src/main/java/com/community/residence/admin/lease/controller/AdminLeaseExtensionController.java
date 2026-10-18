package com.community.residence.admin.lease.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.lease.dto.ReviewLeaseExtensionDTO;
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
public class AdminLeaseExtensionController {
 private final LeaseExtensionService service;
 @PostMapping("/{id}/review") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") @Operation(summary="批准或驳回租期延长")
 public ApiResponse<LeaseExtensionApplication> review(@PathVariable Long leaseId,@PathVariable Long id,@RequestBody @Valid ReviewLeaseExtensionDTO dto){return ApiResponse.success(service.review(leaseId,id,dto));}
}
