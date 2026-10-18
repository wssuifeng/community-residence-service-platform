package com.community.residence.common.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.resident.service.ResidentService;
import com.community.residence.common.resident.vo.ResidentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 居民账号管理控制器：注册/资料/改密/冻结（接口设计.md 9.2.1） */
@Tag(name = "居民账号管理", description = "居民账号与个人资料接口")
@RestController
@RequestMapping("/api/v1/residents")
@RequiredArgsConstructor
public class ResidentController {

    private final ResidentService residentService;

    @Operation(summary = "查询居民信息", description = "管理员/服务人员查询")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'STAFF')")
    @GetMapping("/{id}")
    public ApiResponse<ResidentVO> getById(@PathVariable Long id) {
        return ApiResponse.success(residentService.getById(id));
    }
}
