package com.community.residence.auth.controller;

import com.community.residence.auth.dto.BindCommunityDTO;
import com.community.residence.auth.dto.ChangePasswordDTO;
import com.community.residence.auth.dto.CreateSysUserDTO;
import com.community.residence.auth.dto.UpdateSysUserDTO;
import com.community.residence.auth.dto.UpdateSysUserStatusDTO;
import com.community.residence.auth.service.SysUserService;
import com.community.residence.auth.vo.BoundCommunityVO;
import com.community.residence.auth.vo.SysUserVO;
import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统用户管理控制器：账号 CRUD、冻结、改密、社区绑定（接口设计.md 9.10.1/9.10.2） */
@Tag(name = "系统用户管理", description = "管理员/服务人员账号管理接口")
@RestController
@RequestMapping("/api/v1/sys-users")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;

    @Operation(summary = "创建系统用户", description = "仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<SysUserVO> create(@RequestBody @Valid CreateSysUserDTO dto) {
        return ApiResponse.success(sysUserService.create(dto));
    }

    @Operation(summary = "更新系统用户", description = "仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<SysUserVO> update(@PathVariable Long id, @RequestBody @Valid UpdateSysUserDTO dto) {
        return ApiResponse.success(sysUserService.update(id, dto));
    }

    @Operation(summary = "查询系统用户")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<SysUserVO> getById(@PathVariable Long id) {
        return ApiResponse.success(sysUserService.getById(id));
    }

    @Operation(summary = "系统用户列表（分页）", description = "仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<SysUserVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String role,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String keyword) {
        return ApiResponse.success(sysUserService.page(page, size, role, status, keyword));
    }

    @Operation(summary = "冻结/解冻账号", description = "冻结即时吊销全部令牌；不可冻结自己")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateSysUserStatusDTO dto) {
        sysUserService.updateStatus(id, dto.getStatus(), dto.getReason());
        return ApiResponse.success();
    }

    @Operation(summary = "修改密码", description = "当前登录用户，修改后强制重新登录")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'STAFF')")
    @PatchMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody @Valid ChangePasswordDTO dto) {
        sysUserService.changePassword(dto);
        return ApiResponse.success();
    }

    @Operation(summary = "绑定社区", description = "仅社区管理员角色可绑定；变更后重新登录生效")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/{userId}/communities")
    public ApiResponse<BoundCommunityVO> bindCommunity(@PathVariable Long userId,
                                                       @RequestBody @Valid BindCommunityDTO dto) {
        return ApiResponse.success(sysUserService.bindCommunity(userId, dto));
    }

    @Operation(summary = "解绑社区", description = "变更后重新登录生效")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{userId}/communities/{communityId}")
    public ApiResponse<Void> unbindCommunity(@PathVariable Long userId, @PathVariable Long communityId) {
        sysUserService.unbindCommunity(userId, communityId);
        return ApiResponse.success();
    }

    @Operation(summary = "管理员绑定社区列表")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/{userId}/communities")
    public ApiResponse<List<BoundCommunityVO>> listBoundCommunities(@PathVariable Long userId) {
        return ApiResponse.success(sysUserService.listBoundCommunities(userId));
    }
}
