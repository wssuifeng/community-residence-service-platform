package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.service.CommunityService;
import com.community.residence.workorder.dto.CreateCategoryDTO;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.vo.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 服务类别业务逻辑：二级分类树；删除前校验工单引用 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceCategoryService {

    private final ServiceCategoryMapper categoryMapper;
    private final WorkOrderMapper workOrderMapper;
    private final CommunityService communityService;

    @Transactional(rollbackFor = Exception.class)
    public CategoryVO create(CreateCategoryDTO dto) {
        communityService.requireActiveCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        if (dto.getParentId() != null) {
            ServiceCategory parent = requireCategory(dto.getParentId());
            if (!parent.getCommunityId().equals(dto.getCommunityId())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "父类别不属于该社区");
            }
            if (parent.getParentId() != null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "仅支持二级分类");
            }
        }
        ServiceCategory category = new ServiceCategory();
        applyDto(category, dto);
        category.setIsActive(dto.getIsActive() != null ? dto.getIsActive() : 1);
        categoryMapper.insert(category);
        return CategoryVO.from(category);
    }

    @Transactional(rollbackFor = Exception.class)
    public CategoryVO update(Long id, CreateCategoryDTO dto) {
        ServiceCategory category = requireCategory(id);
        SecurityUtils.checkCommunityAccess(category.getCommunityId());
        if (!category.getCommunityId().equals(dto.getCommunityId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "类别不允许变更所属社区");
        }
        applyDto(category, dto);
        if (dto.getIsActive() != null) {
            category.setIsActive(dto.getIsActive());
        }
        categoryMapper.updateById(category);
        return CategoryVO.from(category);
    }

    /* 删除保护：有工单引用或存在子类别时拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ServiceCategory category = requireCategory(id);
        SecurityUtils.checkCommunityAccess(category.getCommunityId());
        Long orderCount = workOrderMapper.selectCount(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getCategoryId, id));
        if (orderCount > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "类别已被工单引用，无法删除");
        }
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<ServiceCategory>()
                .eq(ServiceCategory::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "类别下存在子类别，无法删除");
        }
        categoryMapper.deleteById(id);
        log.info("服务类别已删除：categoryId={}, operator={}", id, SecurityUtils.getUserId());
    }

    public CategoryVO getById(Long id) {
        return CategoryVO.from(requireCategory(id));
    }

    /** 社区类别树（公开；居民端提交工单时选择类别） */
    public List<CategoryVO> treeByCommunity(Long communityId) {
        List<ServiceCategory> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<ServiceCategory>()
                        .eq(ServiceCategory::getCommunityId, communityId)
                        .orderByAsc(ServiceCategory::getSortOrder)
                        .orderByAsc(ServiceCategory::getId));
        Map<Long, List<CategoryVO>> children = categories.stream()
                .filter(c -> c.getParentId() != null)
                .map(CategoryVO::from)
                .collect(Collectors.groupingBy(CategoryVO::getParentId));
        return categories.stream()
                .filter(c -> c.getParentId() == null)
                .map(c -> {
                    CategoryVO vo = CategoryVO.from(c);
                    vo.setChildren(children.getOrDefault(c.getId(), List.of()));
                    return vo;
                })
                .toList();
    }

    public ServiceCategory requireCategory(Long id) {
        ServiceCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new ResourceNotFoundException("服务类别不存在");
        }
        return category;
    }

    private void applyDto(ServiceCategory category, CreateCategoryDTO dto) {
        category.setCommunityId(dto.getCommunityId());
        category.setName(dto.getName());
        category.setDescription(dto.getDescription());
        category.setParentId(dto.getParentId());
        category.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
    }
}
