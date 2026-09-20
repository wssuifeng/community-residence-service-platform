package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.community.dto.StructureBatchGenerateDTO;
import com.community.residence.community.service.StructureGenerateService;
import com.community.residence.community.vo.StructureBatchGenerateResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区结构链批量生成控制器（C1）：一次请求生成 社区 → 楼栋 → 单元 → 房屋
 * 整条结构链，独立于逐层单建端点（building/unit/house）单独成控，避免与
 * 单资源 CRUD 的路径与语义混杂（接口路径 /api/v1/structures 为结构域聚合动作）。
 */
@Tag(name = "结构链批量生成", description = "社区结构链（楼栋/单元/房屋）一次性批量生成接口")
@RestController
@RequestMapping("/api/v1/structures")
@RequiredArgsConstructor
public class StructureGenerateController {

    private final StructureGenerateService structureGenerateService;

    @Operation(summary = "结构链一次性批量生成",
            description = "按楼栋起止序号 + 单元/楼层/每层房号模板，一次生成 楼栋→单元→房屋 整条链；"
                    + "楼栋名=前缀+序号+后缀、单元名=前缀+序号+后缀（缺省「单元」）、"
                    + "房号=房号前缀+楼层+补零序号（默认两位，1 层 4 号=104）；"
                    + "skipItems 支持前端同一套跳过语法（4 整层 ｜ 4:1 第4层1号 ｜ 04 所有层4号 ｜ "
                    + "*:4 所有层4号 ｜ 104 基础房号 ｜ A-101 完整房号，逗号/顿号/空格分隔）；"
                    + "上限：楼栋 ≤60、单元 ≤600、房屋 ≤5000；楼栋/单元重名逐项失败不中断，"
                    + "房屋逐行失败反馈（部分成功语义）；dryRun=true 仅预览零写入；ADMIN 限绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/batch-generate")
    public ApiResponse<StructureBatchGenerateResultVO> batchGenerate(
            @RequestBody @Valid StructureBatchGenerateDTO dto) {
        /* dryRun 走只读路径：不落库、不写操作日志留痕 */
        return ApiResponse.success(Boolean.TRUE.equals(dto.getDryRun())
                ? structureGenerateService.preview(dto)
                : structureGenerateService.generate(dto));
    }
}
