package com.community.residence.admin.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.dto.BatchCommunityIdsDTO;
import com.community.residence.common.community.dto.BatchCommunityStatusDTO;
import com.community.residence.common.community.dto.CreateCommunityDTO;
import com.community.residence.common.community.dto.UpdateCommunityStatusDTO;
import com.community.residence.admin.community.service.CommunityBatchService;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.community.vo.BatchOperationResultVO;
import com.community.residence.common.community.vo.CommunityVO;
import com.community.residence.common.housing.service.HousingService;
import com.community.residence.common.housing.vo.BuildingHousingTreeVO;
import com.community.residence.common.housing.vo.HouseManageItemVO;
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

/** 小区管理控制器：小区 CRUD 与停用/启用（接口设计.md 9.1.1） */
@Tag(name = "小区管理", description = "小区基础信息管理接口")
@RestController
@RequestMapping("/api/v1/communities")
@RequiredArgsConstructor
public class AdminCommunityController {

    private final CommunityService communityService;
    private final com.community.residence.common.community.service.CommunityResponsibleService communityResponsibleService;
    private final CommunityBatchService communityBatchService;
    private final com.community.residence.admin.community.service.CommunityDeletionService communityDeletionService;
    private final HousingService housingService;

    @Operation(summary = "房屋与房源一体化树（R62）",
            description = "小区内 楼栋→单元→房屋 层级，房屋节点内嵌房源摘要（未挂牌为 null）；"
                    + "ADMIN 限绑定小区，房源独立管理页并入小区结构的读通道")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/{id}/houses-with-housing")
    public ApiResponse<List<BuildingHousingTreeVO>> housesWithHousing(@PathVariable Long id) {
        return ApiResponse.success(housingService.housesWithHousing(id));
    }

    @Operation(summary = "房屋管理分页列表（带图卡片，R62）",
            description = "房屋为主线的分页列表（房屋可未挂牌，挂牌信息为可选子对象 housing）；"
                    + "支持小区/楼栋/单元/房号关键字与挂牌三态（all/listed/unlisted）筛选，"
                    + "communityId 为空时覆盖全部管辖小区；ADMIN 限绑定小区（数据级拦截器过滤）")
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

    @Operation(summary = "创建小区", description = "仅超级管理员可创建小区")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping
    public ApiResponse<CommunityVO> create(@RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.create(dto));
    }

    @Operation(summary = "更新小区", description = "超级管理员全局；小区管理员限绑定小区")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/{id}")
    public ApiResponse<CommunityVO> update(@PathVariable Long id, @RequestBody @Valid CreateCommunityDTO dto) {
        return ApiResponse.success(communityService.update(id, dto));
    }

    @Operation(summary = "更新小区状态", description = "停用/启用小区，仅超级管理员")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody @Valid UpdateCommunityStatusDTO dto) {
        communityService.updateStatus(id, dto.getStatus());
        return ApiResponse.success();
    }

    @Operation(summary = "删除小区（级联）", description = "仅超级管理员；小区退场时其下级结构与关联业务数据一并删除（R1/R6 v1.1），前端须二次确认")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestParam(required=false) String confirmationVersion, @RequestParam(required=false) String confirmationName) {
        communityDeletionService.deleteConfirmed(id, confirmationVersion, confirmationName);
        return ApiResponse.success();
    }

    @Operation(summary="查看小区删除影响",description="仅超管；真实历史和有效居住链计数；仅超管经名称及影响版本确认可删除")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @GetMapping("/{id}/deletion-impact")
    public ApiResponse<com.community.residence.common.community.vo.CommunityDeletionImpactVO> deletionImpact(@PathVariable Long id) {
        return ApiResponse.success(communityDeletionService.deletionImpact(id));
    }

    @Operation(summary = "批量删除小区（级联）",
            description = "仅超级管理员；逐小区独立事务级联删除（复用单小区删除的级联清单），"
                    + "一个失败不影响其余小区（部分成功语义，逐项返回失败原因）；ids 非空、单次上限 50")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PostMapping("/batch-delete")
    public ApiResponse<BatchOperationResultVO> batchDelete(
            @RequestBody @Valid BatchCommunityIdsDTO dto) {
        return ApiResponse.success(communityBatchService.batchDelete(dto));
    }

    @Operation(summary = "批量更新小区状态",
            description = "仅超级管理员；批量停用/启用（ACTIVE/INACTIVE），逐小区独立事务，"
                    + "一个失败不影响其余小区（部分成功语义）；ids 非空、单次上限 50")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PatchMapping("/batch-status")
    public ApiResponse<BatchOperationResultVO> batchUpdateStatus(
            @RequestBody @Valid BatchCommunityStatusDTO dto) {
        return ApiResponse.success(communityBatchService.batchUpdateStatus(dto));
    }
    @Operation(summary="查询小区责任人管理员候选",description="仅ADMIN账号，本小区已绑定者优先，支持分页搜索")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/responsible-admins")
    public ApiResponse<PageVO<com.community.residence.common.community.vo.ResponsibleAdminVO>> responsibleAdmins(
            @RequestParam(required=false) Long communityId, @RequestParam(defaultValue="1") long page,
            @RequestParam(defaultValue="20") long size, @RequestParam(required=false) String keyword) {
        return ApiResponse.success(communityResponsibleService.candidates(communityId,page,size,keyword));
    }
}
