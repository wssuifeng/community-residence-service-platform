package com.community.residence.common.auth.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.auth.service.SysConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 全局配置管理控制器：注册开关等系统级配置（接口设计.md 9.2.4） */
@Tag(name = "全局配置管理", description = "系统全局配置接口")
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
public class ConfigController {

    private final SysConfigService sysConfigService;

    @Operation(summary = "查询配置", description = "公开（注册开关等前端需要）")
    @GetMapping("/{key}")
    public ApiResponse<Map<String, String>> getByKey(@PathVariable String key) {
        return ApiResponse.success(Map.of("key", key, "value", sysConfigService.getValue(key)));
    }
}
