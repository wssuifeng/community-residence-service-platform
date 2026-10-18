package com.community.residence.resident.resident.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.resident.dto.UpdateProfileDTO;
import com.community.residence.common.resident.service.ResidentService;
import com.community.residence.common.resident.vo.ResidentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 居民账号管理控制器：注册/资料/改密/冻结（接口设计.md 9.2.1） */
@Tag(name = "居民账号管理", description = "居民账号与个人资料接口")
@RestController
@RequestMapping("/api/v1/residents")
@RequiredArgsConstructor
public class ResidentAccountController {

    private final ResidentService residentService;

    @Operation(summary = "查询个人资料", description = "居民本人")
    @PreAuthorize("hasRole('RESIDENT')")
    @GetMapping("/profile")
    public ApiResponse<ResidentVO> profile() {
        return ApiResponse.success(residentService.profile());
    }

    @Operation(summary = "更新个人资料")
    @PreAuthorize("hasRole('RESIDENT')")
    @PutMapping("/profile")
    public ApiResponse<ResidentVO> updateProfile(@RequestBody @Valid UpdateProfileDTO dto) {
        return ApiResponse.success(residentService.updateProfile(dto));
    }

    @Operation(summary = "修改密码", description = "修改后强制重新登录")
    @PreAuthorize("hasRole('RESIDENT')")
    @PatchMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid
                                            com.community.residence.common.auth.dto.ChangePasswordDTO dto) {
        residentService.changePassword(dto.getOldPassword(), dto.getNewPassword());
        return ApiResponse.success();
    }
}
