package com.community.residence.common.lease.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.lease.entity.LeaseExtensionApplication;
import com.community.residence.common.lease.service.LeaseExtensionService;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
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
}
