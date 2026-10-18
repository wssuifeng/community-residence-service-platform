package com.community.residence.common.auth.controller;

import com.community.residence.common.auth.dto.ChangePasswordDTO;
import com.community.residence.common.auth.service.SysUserService;
import com.community.residence.common.web.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 系统用户管理控制器：账号 CRUD、冻结、改密、社区绑定（接口设计.md 9.10.1/9.10.2） */
@Tag(name = "系统用户管理", description = "管理员/服务人员账号管理接口")
@RestController
@RequestMapping("/api/v1/sys-users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;

    @Operation(summary = "修改密码", description = "当前登录用户，修改后强制重新登录")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'STAFF')")
    @PatchMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordDTO dto) {
        sysUserService.changePassword(dto);
        return ApiResponse.success();
    }
}
