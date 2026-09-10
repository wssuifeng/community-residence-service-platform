package com.community.residence.resident.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.resident.dto.AdminCreateResidentDTO;
import com.community.residence.resident.dto.UpdateProfileDTO;
import com.community.residence.resident.dto.UpdateResidentStatusDTO;
import com.community.residence.resident.service.ResidentService;
import com.community.residence.resident.vo.AdminCreateResidentVO;
import com.community.residence.resident.vo.ResidentImportVO;
import com.community.residence.resident.vo.ResidentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 居民账号管理控制器：注册/资料/改密/冻结（接口设计.md 9.2.1） */
@Tag(name = "居民账号管理", description = "居民账号与个人资料接口")
@RestController
@RequestMapping("/api/v1/residents")
@RequiredArgsConstructor
public class ResidentController {

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
                                            com.community.residence.auth.dto.ChangePasswordDTO dto) {
        residentService.changePassword(dto.getOldPassword(), dto.getNewPassword());
        return ApiResponse.success();
    }

    @Operation(summary = "查询居民信息", description = "管理员/服务人员查询")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN', 'STAFF')")
    @GetMapping("/{id}")
    public ApiResponse<ResidentVO> getById(@PathVariable Long id) {
        return ApiResponse.success(residentService.getById(id));
    }

    @Operation(summary = "居民列表（分页）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<ResidentVO>> page(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword) {
        return ApiResponse.success(residentService.page(page, size, status, keyword));
    }

    @Operation(summary = "冻结/解冻账号", description = "冻结即时吊销全部令牌")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateResidentStatusDTO dto) {
        residentService.updateStatus(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "代建居民账号", description = "R8 v1.2；初始密码服务端生成（手机号后 6 位）明文仅本次返回；ADMIN 限绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/admin-create")
    public ApiResponse<AdminCreateResidentVO> adminCreate(@RequestBody @Valid AdminCreateResidentDTO dto) {
        return ApiResponse.success(residentService.adminCreate(dto));
    }

    @Operation(summary = "批量导入居民（CSV）", description = "R8 v1.2；UTF-8 CSV，表头：社区ID,姓名,手机号,证件号；部分成功语义，失败行返回行号与原因")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResidentImportVO> importCsv(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.success(residentService.importCsv(null));
        }
        try {
            return ApiResponse.success(residentService.importCsv(file.getInputStream()));
        } catch (java.io.IOException e) {
            throw new com.community.residence.common.exception.BusinessException(
                    com.community.residence.common.constant.ErrorCode.INVALID_PARAM, "CSV 文件读取失败");
        }
    }
}
