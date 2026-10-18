package com.community.residence.admin.auth.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.auth.dto.UpdateConfigDTO;
import com.community.residence.common.auth.entity.SysConfig;
import com.community.residence.common.auth.service.SysConfigService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 全局配置管理控制器：注册开关等系统级配置（接口设计.md 9.2.4） */
@Tag(name = "全局配置管理", description = "系统全局配置接口")
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class AdminConfigController {

    private final SysConfigService sysConfigService;

    @Operation(summary = "配置列表", description = "仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<List<SysConfig>> list() {
        return ApiResponse.success(sysConfigService.listAll());
    }

    @Operation(summary = "更新配置", description = "仅超级管理员；变更即时生效（缓存清理）")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{key}")
    public ApiResponse<Void> update(@PathVariable String key, @RequestBody @Valid UpdateConfigDTO dto) {
        sysConfigService.update(key, dto.getValue());
        return ApiResponse.success();
    }
}
