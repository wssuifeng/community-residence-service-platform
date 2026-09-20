package com.community.residence.agreement.controller;

import com.community.residence.agreement.dto.AgreementTemplateDTO;
import com.community.residence.agreement.service.AgreementService;
import com.community.residence.agreement.vo.AgreementTemplateVO;
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

/** 租赁协议模板控制器（接口设计.md 9.3.2，R64）：模板维护 + 可发起协议的可选清单 */
@Tag(name = "租赁协议模板", description = "协议模板维护（含模板附件上传后登记）与发起协议可选清单")
@RestController
@RequestMapping("/api/v1/agreement-templates")
@RequiredArgsConstructor
public class AgreementTemplateController {

    private final AgreementService agreementService;

    @Operation(summary = "协议模板列表（分页）", description = "社区管理员可见全局模板与本社区模板；超管可见全部")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<PageVO<AgreementTemplateVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(agreementService.pageTemplates(page, size, communityId, status));
    }

    @Operation(summary = "某社区可用的协议模板清单", description = "本社区模板 + 全局模板，默认模板排最前")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/available")
    public ApiResponse<List<AgreementTemplateVO>> available(@RequestParam Long communityId) {
        return ApiResponse.success(agreementService.availableTemplates(communityId));
    }

    @Operation(summary = "协议模板详情")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}")
    public ApiResponse<AgreementTemplateVO> getById(@PathVariable Long id) {
        return ApiResponse.success(agreementService.getTemplate(id));
    }

    @Operation(summary = "创建协议模板", description = "正文与模板附件至少填写一项；communityId 为空则为全局模板（仅超管）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<AgreementTemplateVO> create(@RequestBody @Valid AgreementTemplateDTO dto) {
        return ApiResponse.success(agreementService.createTemplate(dto));
    }

    @Operation(summary = "更新协议模板")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<AgreementTemplateVO> update(@PathVariable Long id,
                                                   @RequestBody @Valid AgreementTemplateDTO dto) {
        return ApiResponse.success(agreementService.updateTemplate(id, dto));
    }

    @Operation(summary = "启用/停用协议模板")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<AgreementTemplateVO> updateStatus(@PathVariable Long id,
                                                        @RequestParam String status) {
        return ApiResponse.success(agreementService.updateTemplateStatus(id, status));
    }

    @Operation(summary = "删除协议模板", description = "已生成协议保存正文快照，删除模板不影响历史协议")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        agreementService.deleteTemplate(id);
        return ApiResponse.success();
    }
}
