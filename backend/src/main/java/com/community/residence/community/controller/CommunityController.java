package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.BatchCommunityIdsDTO;
import com.community.residence.community.dto.BatchCommunityStatusDTO;
import com.community.residence.community.dto.CreateCommunityDTO;
import com.community.residence.community.dto.UpdateCommunityStatusDTO;
import com.community.residence.community.service.CommunityBatchService;
import com.community.residence.community.service.CommunityService;
import com.community.residence.community.vo.BatchOperationResultVO;
import com.community.residence.community.vo.CommunityVO;
import com.community.residence.housing.service.HousingService;
import com.community.residence.housing.vo.BuildingHousingTreeVO;
import com.community.residence.housing.vo.HouseManageItemVO;
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

/** 社区管理控制器：社区 CRUD 与停用/启用（接口设计.md 9.1.1） */
@Tag(name = "社区管理", description = "社区基础信息管理接口")
@RestController
@RequestMapping("/api/v1/communities")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;
    private final CommunityBatchService communityBatchService;
    private final HousingService housingService;

    @Operation(summary = "房屋与房源一体化树（R62）",
            description = "社区内 楼栋→单元→房屋 层级，房屋节点内嵌房源摘要（未挂牌为 null）；"
                    + "ADMIN 限绑定社区，房源独立管理页并入社区结构的读通道")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/houses-with-housing")
    public ApiResponse<List<BuildingHousingTreeVO>> housesWithHousing(@PathVariable Long id) {
        return ApiResponse.success(housingService.housesWithHousing(id));
    }

    @Operation(summary = "房屋管理分页列表（带图卡片，R62）",
            description = "房屋为主线的分页列表（房屋可未挂牌，挂牌信息为可选子对象 housing）；"
                    + "支持社区/楼栋/单元/房号关键字与挂牌三态（all/listed/unlisted）筛选，"
                    + "communityId 为空时覆盖全部管辖社区；ADMIN 限绑定社区（数据级拦截器过滤）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/houses-manage")
    public ApiResponse<PageVO<HouseManageItemVO>> housesManage(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) Long buildingId,
            @RequestParam(required = false) Long unitId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String listing) {
        return ApiResponse.success(housingService.pageHouseManage(communityId, buildingId, unitId,
                keyword, listing, page, size));
    }

    @Operation(summary = "创建社区", description = "仅超级管理员可创建社区")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<CommunityVO> create(@RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.create(dto));
    }

    @Operation(summary = "更新社区", description = "超级管理员全局；社区管理员限绑定社区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<CommunityVO> update(@PathVariable Long id, @RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.update(id, dto));
    }

    @Operation(summary = "查询社区详情")
    @GetMapping("/{id}")
    public ApiResponse<CommunityVO> getById(@PathVariable Long id) {
        return ApiResponse.success(communityService.getById(id));
    }

    @Operation(summary = "社区列表（分页）")
    @GetMapping
    public ApiResponse<PageVO<CommunityVO>> page(@RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword) {
        return ApiResponse.success(communityService.page(page, size, status, keyword));
    }

    @Operation(summary = "更新社区状态", description = "停用/启用社区，仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateCommunityStatusDTO dto) {
        communityService.updateStatus(id, dto.getStatus());
        return ApiResponse.success();
    }

    @Operation(summary = "删除社区（级联）", description = "仅超级管理员；社区退场时其下级结构与关联业务数据一并删除（R1/R6 v1.1），前端须二次确认")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        communityService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "批量删除社区（级联）",
            description = "仅超级管理员；逐社区独立事务级联删除（复用单社区删除的级联清单），"
                    + "一个失败不影响其余社区（部分成功语义，逐项返回失败原因）；ids 非空、单次上限 50")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/batch-delete")
    public ApiResponse<BatchOperationResultVO> batchDelete(
            @RequestBody @Valid BatchCommunityIdsDTO dto) {
        return ApiResponse.success(communityBatchService.batchDelete(dto));
    }

    @Operation(summary = "批量更新社区状态",
            description = "仅超级管理员；批量停用/启用（ACTIVE/INACTIVE），逐社区独立事务，"
                    + "一个失败不影响其余社区（部分成功语义）；ids 非空、单次上限 50")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/batch-status")
    public ApiResponse<BatchOperationResultVO> batchUpdateStatus(
            @RequestBody @Valid BatchCommunityStatusDTO dto) {
        return ApiResponse.success(communityBatchService.batchUpdateStatus(dto));
    }
}
