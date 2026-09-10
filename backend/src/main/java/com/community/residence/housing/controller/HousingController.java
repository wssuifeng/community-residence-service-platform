package com.community.residence.housing.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.housing.dto.CreateHousingDTO;
import com.community.residence.housing.dto.UpdateHousingStatusDTO;
import com.community.residence.housing.service.HousingService;
import com.community.residence.housing.vo.HousingVO;
import com.community.residence.reservation.vo.AvailableSlotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 房源管理控制器：上架/下架 + 游客浏览（接口设计.md 9.12.1） */
@Tag(name = "房源管理", description = "房源展示与管理接口")
@RestController
@RequestMapping("/api/v1/housings")
@RequiredArgsConstructor
public class HousingController {

    private final HousingService housingService;

    @Operation(summary = "创建房源", description = "同一房屋仅允许一条在架房源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<HousingVO> create(@RequestBody @Valid CreateHousingDTO dto) {
        return ApiResponse.success(housingService.create(dto));
    }

    @Operation(summary = "更新房源")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<HousingVO> update(@PathVariable Long id, @RequestBody @Valid CreateHousingDTO dto) {
        return ApiResponse.success(housingService.update(id, dto));
    }

    @Operation(summary = "删除房源", description = "有未完成看房预约时不可删除")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        housingService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "查询房源详情", description = "公开；已出租/下线房源游客不可见")
    @GetMapping("/{id}")
    public ApiResponse<HousingVO> getById(@PathVariable Long id) {
        return ApiResponse.success(housingService.getById(id));
    }

    @Operation(summary = "房源列表（分页）", description = "公开；支持社区/状态/租金/关键词/户型/租售类型过滤（R53 v1.2）")
    @GetMapping
    public ApiResponse<PageVO<HousingVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) Long communityId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) BigDecimal minRent,
                                               @RequestParam(required = false) BigDecimal maxRent,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String layout,
                                               @RequestParam(required = false) String rentType) {
        return ApiResponse.success(housingService.page(page, size, communityId, status,
                minRent, maxRent, keyword, layout, rentType));
    }

    @Operation(summary = "更新房源状态", description = "可租/已预订/已出租/已下线")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateHousingStatusDTO dto) {
        housingService.updateStatus(id, dto);
        return ApiResponse.success();
    }

    @Operation(summary = "记录房源浏览", description = "公开；游客/居民均可")
    @PostMapping("/{id}/view")
    public ApiResponse<Void> recordView(@PathVariable Long id) {
        housingService.recordView(id);
        return ApiResponse.success();
    }

    @Operation(summary = "查询看房可预约时段", description = "公开；周循环模板按日期范围展开 + 占用计数（接口设计 9.12.2.8）")
    @GetMapping("/{id}/available-slots")
    public ApiResponse<List<AvailableSlotVO>> availableSlots(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(housingService.availableSlots(id, startDate, endDate));
    }
}
