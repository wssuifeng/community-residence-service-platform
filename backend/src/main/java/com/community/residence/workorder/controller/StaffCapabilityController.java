package com.community.residence.workorder.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.workorder.dto.SaveStaffCapabilityDTO;
import com.community.residence.workorder.service.StaffCapabilityService;
import com.community.residence.workorder.vo.StaffCandidateVO;
import com.community.residence.workorder.vo.StaffCapabilityVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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
public class StaffCapabilityController {

    private final StaffCapabilityService staffCapabilityService;

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

    @Operation(summary = "可绑定人员候选", description = "全量 STAFF 账号（id/姓名/用户名），供绑定选择；不做社区过滤")
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

    @Operation(summary = "保存服务人员能力绑定", description = "全量覆盖式：先删后插；列表为空或不传表示清空该维度")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{staffId}")
    public ApiResponse<StaffCapabilityVO> save(@PathVariable Long staffId,
                                               @RequestBody @Valid SaveStaffCapabilityDTO dto) {
        return ApiResponse.success(staffCapabilityService.save(staffId, dto));
    }
}
