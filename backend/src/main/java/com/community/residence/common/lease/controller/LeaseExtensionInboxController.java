package com.community.residence.common.lease.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.lease.entity.LeaseExtensionApplication;
import com.community.residence.common.lease.service.LeaseExtensionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
@RestController @RequestMapping("/api/v1/lease-extensions") @RequiredArgsConstructor @Tag(name="租期延长")
public class LeaseExtensionInboxController {
 private final LeaseExtensionService service;
 @GetMapping("/pending") @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") @Operation(summary="管辖租约待审延长申请")
 public ApiResponse<List<LeaseExtensionApplication>> pending(){return ApiResponse.success(service.pending());}
}
