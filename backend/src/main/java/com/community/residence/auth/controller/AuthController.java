package com.community.residence.auth.controller;

import com.community.residence.auth.dto.LoginDTO;
import com.community.residence.auth.service.AuthService;
import com.community.residence.auth.vo.LoginVO;
import com.community.residence.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证控制器：居民端/管理端双登录入口（接口设计.md 9.2.1 / 9.10.1） */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 居民端登录（resident 表，签发 RESIDENT 令牌） */
    @Operation(summary = "居民登录")
    @PostMapping("/resident/login")
    public ApiResponse<LoginVO> residentLogin(@RequestBody @Valid LoginDTO dto) {
        return ApiResponse.success(authService.residentLogin(dto));
    }

    /** 居民端登出（令牌入黑名单） */
    @Operation(summary = "居民登出")
    @PostMapping("/resident/logout")
    public ApiResponse<Void> residentLogout(
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization) {
        authService.logout(bearerToken(authorization));
        return ApiResponse.success();
    }

    /** 管理端登录（sys_user 表：SUPER_ADMIN/ADMIN/STAFF，ADMIN 附绑定社区） */
    @Operation(summary = "管理员登录")
    @PostMapping("/admin/login")
    public ApiResponse<LoginVO> adminLogin(@RequestBody @Valid LoginDTO dto) {
        return ApiResponse.success(authService.adminLogin(dto));
    }

    /** 管理端登出（令牌入黑名单） */
    @Operation(summary = "管理员登出")
    @PostMapping("/admin/logout")
    public ApiResponse<Void> adminLogout(
            @org.springframework.web.bind.annotation.RequestHeader("Authorization") String authorization) {
        authService.logout(bearerToken(authorization));
        return ApiResponse.success();
    }

    private String bearerToken(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : "";
    }
}
