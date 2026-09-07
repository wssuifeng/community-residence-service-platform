package com.community.residence.auth.controller;

import com.community.residence.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅用于认证冒烟测试（后随模块开发删除） */
@Tag(name = "临时测试")
@RestController
@RequestMapping("/api/v1/_smoke")
public class SmokeTestController {

    @Operation(summary = "登录用户可见")
    @GetMapping("/me")
    public ApiResponse<String> me() {
        return ApiResponse.success("authenticated");
    }

    @Operation(summary = "仅超管可见")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/super")
    public ApiResponse<String> superOnly() {
        return ApiResponse.success("super-admin-only");
    }
}
