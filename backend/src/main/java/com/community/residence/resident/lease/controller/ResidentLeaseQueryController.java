package com.community.residence.resident.lease.controller;
import com.community.residence.common.web.result.*;
import com.community.residence.resident.lease.service.ResidentLeaseQueryService;
import com.community.residence.resident.lease.vo.ResidentLeaseSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
/** 本人租约卡片摘要。 */
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/leases") @Tag(name="居民租约摘要")
public class ResidentLeaseQueryController {
 private final ResidentLeaseQueryService service;
 @GetMapping("/my-summaries") @PreAuthorize("hasRole('RESIDENT')") @Operation(summary="分页读取本人租约卡片摘要")
 public ApiResponse<PageVO<ResidentLeaseSummaryVO>> page(@RequestParam(defaultValue="1") long page,@RequestParam(defaultValue="10") long size) {return ApiResponse.success(service.page(page,size));}
}
