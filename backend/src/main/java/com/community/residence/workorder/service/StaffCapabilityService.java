package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.workorder.dto.SaveStaffCapabilityDTO;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.StaffCommunity;
import com.community.residence.workorder.entity.StaffServiceCategory;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.StaffCommunityMapper;
import com.community.residence.workorder.mapper.StaffServiceCategoryMapper;
import com.community.residence.workorder.vo.StaffCandidateVO;
import com.community.residence.workorder.vo.StaffCapabilityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务人员能力绑定业务逻辑（V19，C4 物业调度）：人员-常驻社区 + 人员-擅长服务类别。
 * 服务人员即 sys_user 中 role=STAFF 的账号，无独立人员表；sys_user 在数据级权限
 * 拦截器 SKIP_TABLES 中，故 ADMIN 的可见范围（限绑定社区内已绑定人员）在本层
 * 经 staff_community 显式收敛，不依赖拦截器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffCapabilityService {

    private final SysUserMapper sysUserMapper;
    private final StaffCommunityMapper staffCommunityMapper;
    private final StaffServiceCategoryMapper staffServiceCategoryMapper;
    private final CommunityMapper communityMapper;
    private final ServiceCategoryMapper categoryMapper;

    /* 人员能力列表：SUPER_ADMIN 看全部 STAFF；ADMIN 只看绑定了其绑定社区的人员
       （经 staff_community 显式收敛）。communityId/categoryId/keyword 为可选筛选，
       关键词为空时不额外收敛，未绑定任何社区的 STAFF 在超管视图下照常可见 */
    public PageVO<StaffCapabilityVO> page(long page, long size, Long communityId,
                                          Long categoryId, String keyword) {
        List<Long> scopedStaffIds = resolveScopeIds(communityId, categoryId);
        if (scopedStaffIds != null && scopedStaffIds.isEmpty()) {
            return PageVO.of(List.of(), 0, page, size);
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRole, RoleConstants.STAFF)
                .in(scopedStaffIds != null, SysUser::getId, scopedStaffIds == null ? List.of() : scopedStaffIds)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(SysUser::getRealName, keyword)
                        .or().like(SysUser::getUsername, keyword)
                        .or().like(SysUser::getPhone, keyword))
                .orderByAsc(SysUser::getId);
        Page<SysUser> result = sysUserMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.getRecords() == null ? List.of() : assemble(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 人员能力详情：ADMIN 限绑定社区内已绑定人员（范围外 404） */
    public StaffCapabilityVO getById(Long staffId) {
        SysUser staff = requireStaff(staffId);
        if (SecurityUtils.hasRole(RoleConstants.ADMIN) && !inAdminScope(staffId)) {
            throw new ResourceNotFoundException("服务人员不存在");
        }
        return assemble(List.of(staff)).get(0);
    }

    /* 全量覆盖式保存绑定：先删后插同一事务。校验约束——
       staffId 须为存在且角色 STAFF 的账号；communityIds 须逐个通过社区归属校验
       （ADMIN 限绑定社区，SUPER_ADMIN 不限）且真实存在；categoryIds 须真实存在 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "STAFF_CAPABILITY", targetId = "#staffId", content = "'保存服务人员能力绑定'")
    public StaffCapabilityVO save(Long staffId, SaveStaffCapabilityDTO dto) {
        SysUser staff = requireStaff(staffId);
        List<Long> communityIds = normalizeIds(dto == null ? null : dto.getCommunityIds());
        List<Long> categoryIds = normalizeIds(dto == null ? null : dto.getCategoryIds());

        for (Long communityId : communityIds) {
            SecurityUtils.checkCommunityAccess(communityId);
            if (communityMapper.selectById(communityId) == null) {
                throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "社区不存在：" + communityId);
            }
        }
        for (Long categoryId : categoryIds) {
            if (categoryMapper.selectById(categoryId) == null) {
                throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务类别不存在：" + categoryId);
            }
        }

        staffCommunityMapper.delete(new LambdaQueryWrapper<StaffCommunity>()
                .eq(StaffCommunity::getStaffId, staffId));
        staffServiceCategoryMapper.delete(new LambdaQueryWrapper<StaffServiceCategory>()
                .eq(StaffServiceCategory::getStaffId, staffId));
        for (Long communityId : communityIds) {
            StaffCommunity binding = new StaffCommunity();
            binding.setStaffId(staffId);
            binding.setCommunityId(communityId);
            staffCommunityMapper.insert(binding);
        }
        for (Long categoryId : categoryIds) {
            StaffServiceCategory binding = new StaffServiceCategory();
            binding.setStaffId(staffId);
            binding.setCategoryId(categoryId);
            staffServiceCategoryMapper.insert(binding);
        }
        log.info("服务人员能力绑定已保存：staffId={}, communities={}, categories={}, operator={}",
                staffId, communityIds, categoryIds, SecurityUtils.getUserId());
        return assemble(List.of(staff)).get(0);
    }

    /** 可绑定人员候选池：全量 STAFF（仅功能级权限，不做社区过滤，供绑定下拉选择） */
    public List<StaffCandidateVO> candidates() {
        return sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, RoleConstants.STAFF)
                        .orderByAsc(SysUser::getId))
                .stream().map(StaffCandidateVO::from).toList();
    }

    /** 批量取人员的常驻社区 ID（单次批量查询，避免逐人查询；无绑定的人不出现在 Map 中） */
    public Map<Long, List<Long>> communityIdsByStaff(Collection<Long> staffIds) {
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (StaffCommunity row : listStaffCommunity(staffIds)) {
            result.computeIfAbsent(row.getStaffId(), k -> new ArrayList<>()).add(row.getCommunityId());
        }
        return result;
    }

    /** 批量取人员的擅长类别 ID（单次批量查询，避免逐人查询） */
    public Map<Long, List<Long>> categoryIdsByStaff(Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (StaffServiceCategory row : staffServiceCategoryMapper.selectList(
                new LambdaQueryWrapper<StaffServiceCategory>()
                        .in(StaffServiceCategory::getStaffId, staffIds)
                        .orderByAsc(StaffServiceCategory::getId))) {
            result.computeIfAbsent(row.getStaffId(), k -> new ArrayList<>()).add(row.getCategoryId());
        }
        return result;
    }

    /** 批量取人员常驻社区名称（逗号拼接，按绑定顺序；用于派单候选的归属提示） */
    public Map<Long, String> communityNamesByStaff(Collection<Long> staffIds) {
        Map<Long, List<Long>> bound = communityIdsByStaff(staffIds);
        if (bound.isEmpty()) {
            return Map.of();
        }
        Set<Long> communityIds = new LinkedHashSet<>();
        bound.values().forEach(communityIds::addAll);
        Map<Long, String> names = communityNames(communityIds);
        Map<Long, String> result = new LinkedHashMap<>();
        bound.forEach((staffId, ids) -> result.put(staffId, ids.stream()
                .map(id -> names.getOrDefault(id, String.valueOf(id)))
                .collect(java.util.stream.Collectors.joining("，"))));
        return result;
    }

    /** 校验服务人员账号（角色须为 STAFF） */
    public SysUser requireStaff(Long staffId) {
        SysUser staff = staffId == null ? null : sysUserMapper.selectById(staffId);
        if (staff == null || !RoleConstants.STAFF.equals(staff.getRole())) {
            throw new ResourceNotFoundException("服务人员不存在");
        }
        return staff;
    }

    /* 分页收敛条件合并：ADMIN 绑定社区范围 ∩ communityId 筛选 ∩ categoryId 筛选；
       三者皆无则返回 null（不额外收敛） */
    private List<Long> resolveScopeIds(Long communityId, Long categoryId) {
        List<Long> scoped = null;
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            Set<Long> bound = SecurityUtils.getCommunityIds();
            scoped = bound == null || bound.isEmpty() ? List.of() : staffIdsBoundTo(bound);
        }
        if (communityId != null) {
            scoped = intersect(scoped, staffIdsBoundTo(List.of(communityId)));
        }
        if (categoryId != null) {
            List<Long> byCategory = staffServiceCategoryMapper.selectList(
                            new LambdaQueryWrapper<StaffServiceCategory>()
                                    .eq(StaffServiceCategory::getCategoryId, categoryId)
                                    .orderByAsc(StaffServiceCategory::getId))
                    .stream().map(StaffServiceCategory::getStaffId).distinct().toList();
            scoped = intersect(scoped, byCategory);
        }
        return scoped;
    }

    /* ADMIN 视角下人员是否在可见范围内（绑定了其任一绑定社区） */
    private boolean inAdminScope(Long staffId) {
        Set<Long> bound = SecurityUtils.getCommunityIds();
        return bound != null && !bound.isEmpty() && staffIdsBoundTo(bound).contains(staffId);
    }

    /* 指定社区集合内已绑定的人员 ID（staff_community 为 ADMIN 可见性唯一来源） */
    private List<Long> staffIdsBoundTo(Collection<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) {
            return List.of();
        }
        return staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                        .in(StaffCommunity::getCommunityId, communityIds)
                        .orderByAsc(StaffCommunity::getId))
                .stream().map(StaffCommunity::getStaffId).distinct().toList();
    }

    private List<StaffCommunity> listStaffCommunity(Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return List.of();
        }
        return staffCommunityMapper.selectList(new LambdaQueryWrapper<StaffCommunity>()
                .in(StaffCommunity::getStaffId, staffIds)
                .orderByAsc(StaffCommunity::getId));
    }

    private Map<Long, String> communityNames(Collection<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Community community : communityMapper.selectList(new LambdaQueryWrapper<Community>()
                .in(Community::getId, communityIds))) {
            names.put(community.getId(), community.getName());
        }
        return names;
    }

    private Map<Long, String> categoryNames(Collection<Long> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (ServiceCategory category : categoryMapper.selectList(new LambdaQueryWrapper<ServiceCategory>()
                .in(ServiceCategory::getId, categoryIds))) {
            names.put(category.getId(), category.getName());
        }
        return names;
    }

    /* 组装能力视图：社区/类别绑定与名称三次批量查询（社区、类别、名称），无逐人查询 */
    private List<StaffCapabilityVO> assemble(List<SysUser> staffList) {
        List<Long> staffIds = staffList.stream().map(SysUser::getId).toList();
        Map<Long, List<Long>> communityBindings = communityIdsByStaff(staffIds);
        Map<Long, List<Long>> categoryBindings = categoryIdsByStaff(staffIds);
        Set<Long> communityIds = new LinkedHashSet<>();
        communityBindings.values().forEach(communityIds::addAll);
        Set<Long> categoryIds = new LinkedHashSet<>();
        categoryBindings.values().forEach(categoryIds::addAll);
        Map<Long, String> communityNameMap = communityNames(communityIds);
        Map<Long, String> categoryNameMap = categoryNames(categoryIds);

        List<StaffCapabilityVO> records = new ArrayList<>(staffList.size());
        for (SysUser staff : staffList) {
            List<Long> boundCommunities = communityBindings.getOrDefault(staff.getId(), List.of());
            List<Long> boundCategories = categoryBindings.getOrDefault(staff.getId(), List.of());
            StaffCapabilityVO vo = new StaffCapabilityVO();
            vo.setStaffId(staff.getId());
            vo.setUsername(staff.getUsername());
            vo.setRealName(staff.getRealName());
            vo.setPhone(staff.getPhone());
            vo.setStatus(staff.getStatus());
            vo.setCommunityIds(boundCommunities);
            vo.setCommunityNames(boundCommunities.stream()
                    .map(id -> communityNameMap.getOrDefault(id, String.valueOf(id))).toList());
            vo.setCategoryIds(boundCategories);
            vo.setCategoryNames(boundCategories.stream()
                    .map(id -> categoryNameMap.getOrDefault(id, String.valueOf(id))).toList());
            records.add(vo);
        }
        return records;
    }

    /* 交集收敛：左值为 null（未收敛）时取右值；任一为空则结果为空 */
    private List<Long> intersect(List<Long> left, List<Long> right) {
        if (left == null) {
            return right;
        }
        List<Long> merged = left.stream().filter(right::contains).distinct().toList();
        return merged;
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }
}
