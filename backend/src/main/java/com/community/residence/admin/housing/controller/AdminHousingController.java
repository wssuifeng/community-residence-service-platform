package com.community.residence.admin.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.dto.BatchGenerateHousingDTO;
import com.community.residence.common.housing.dto.CreateHousingDTO;
import com.community.residence.common.housing.dto.UpdateHousingStatusDTO;
import com.community.residence.common.housing.service.HousingService;
import com.community.residence.common.housing.vo.BatchGenerateResultVO;
import com.community.residence.common.housing.vo.HousingVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 房源管理控制器：上架/下架 + 公开浏览（接口设计.md 9.12.1） */
@Tag(name = "房源管理", description = "房源展示与管理接口")
@RestController
@RequestMapping("/api/v1/housings")
@RequiredArgsConstructor
public class AdminHousingController {

    private final HousingService housingService;

    @Operation(summary = "创建房源", description = "同一房屋仅允许一条在架房源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<HousingVO> create(@RequestBody @Valid CreateHousingDTO dto) {
        return ApiResponse.success(housingService.create(dto));
    }

    @Operation(summary = "按小区批量挂牌（R62）",
            description = "为小区内无在架房源的房屋批量生成 AVAILABLE 房源（默认月租/押金/租售类型参数化，"
                    + "标题「{楼栋}{单元}{房号}·精装房源」）；已有在架房源的房屋跳过（幂等）；ADMIN 限绑定小区。"
                    + "可同时为本次新建房源建立可预约看房时段（createTimeslots 缺省 true，"
                    + "timeslotMode 缺省 DEFAULT=工作日 09:00-12:00 + 14:00-18:00）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/batch-generate")
    public ApiResponse<BatchGenerateResultVO> batchGenerate(
            @RequestBody @Valid BatchGenerateHousingDTO dto) {
        return ApiResponse.success(housingService.batchGenerate(dto));
    }

    @Operation(summary = "更新房源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<HousingVO> update(@PathVariable Long id, @RequestBody @Valid CreateHousingDTO dto) {
        return ApiResponse.success(housingService.update(id, dto));
    }

    @Operation(summary = "删除房源", description = "有未完成看房预约，或曾被租住占用的挂牌不可物理删除；停止公开请下线")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        housingService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "更新房源状态", description = "可租/已预订/已出租/已下线")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateHousingStatusDTO dto) {
        housingService.updateStatus(id, dto);
        return ApiResponse.success();
    }
}
