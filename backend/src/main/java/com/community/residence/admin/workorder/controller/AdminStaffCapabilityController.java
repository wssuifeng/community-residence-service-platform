package com.community.residence.admin.workorder.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.workorder.dto.SaveStaffCapabilityDTO;
import com.community.residence.common.workorder.service.StaffCapabilityService;
import com.community.residence.common.workorder.vo.StaffCandidateVO;
import com.community.residence.common.workorder.vo.StaffCapabilityVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 服务人员能力绑定控制器：人员-常驻社区/擅长服务类别（V19，C4 派单调度基础数据） */
@Tag(name = "服务人员能力绑定", description = "常驻社区与擅长服务类别绑定接口")
@RestController
@RequestMapping("/api/v1/staff-capabilities")
@RequiredArgsConstructor
public class AdminStaffCapabilityController {

    private final StaffCapabilityService staffCapabilityService;

    @Operation(summary = "代建服务人员账号", description = "固定STAFF，强制同事务绑定至少一个有效有权社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/accounts")
    public ApiResponse<StaffCapabilityVO> createAccount(
            @RequestBody @Valid com.community.residence.common.workorder.dto.CreateStaffAccountDTO dto) {
        return ApiResponse.success(staffCapabilityService.createAccount(dto));
    }

    @Operation(summary = "服务人员能力列表", description = "分页；超管看全部 STAFF，社区管理员仅见绑定其社区的人员")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<StaffCapabilityVO>> page(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size,
                                                       @RequestParam(required = false) Long communityId,
                                                       @RequestParam(required = false) Long categoryId,
                                                       @RequestParam(required = false) String keyword) {
        return ApiResponse.success(staffCapabilityService.page(page, size, communityId, categoryId, keyword));
    }

    @Operation(summary = "业务人员候选", description = "社区管理员仅见授权社区已绑定人员")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/candidates")
    public ApiResponse<List<StaffCandidateVO>> candidates() {
        return ApiResponse.success(staffCapabilityService.candidates());
    }

    @Operation(summary = "服务人员能力详情", description = "含常驻社区与擅长类别；社区管理员限绑定社区内人员")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{staffId}")
    public ApiResponse<StaffCapabilityVO> getById(@PathVariable Long staffId) {
        return ApiResponse.success(staffCapabilityService.getById(staffId));
    }

    @Operation(summary = "保存服务人员能力绑定", description = "社区管理员仅覆盖自身授权社区及所属类别；保留其他社区绑定")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{staffId}")
    public ApiResponse<StaffCapabilityVO> save(@PathVariable Long staffId,
                                               @RequestBody @Valid SaveStaffCapabilityDTO dto) {
        return ApiResponse.success(staffCapabilityService.save(staffId, dto));
    }
}
