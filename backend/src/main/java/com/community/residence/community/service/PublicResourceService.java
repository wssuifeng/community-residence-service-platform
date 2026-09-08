package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateResourceDTO;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.mapper.PublicResourceMapper;
import com.community.residence.community.vo.ResourceVO;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 公共资源业务逻辑：删除前校验未完成预约 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicResourceService {

    /** 未完成预约状态集合（删除保护判定用） */
    private static final List<String> ACTIVE_RESERVATION_STATUS = List.of("PENDING", "RESERVED");

    private final PublicResourceMapper resourceMapper;
    private final ResourceReservationMapper reservationMapper;
    private final CommunityService communityService;

    @Transactional(rollbackFor = Exception.class)
    public ResourceVO create(CreateResourceDTO dto) {
        communityService.requireActiveCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        PublicResource resource = new PublicResource();
        applyDto(resource, dto);
        resourceMapper.insert(resource);
        return fillCommunityName(ResourceVO.from(resource));
    }

    @Transactional(rollbackFor = Exception.class)
    public ResourceVO update(Long id, CreateResourceDTO dto) {
        PublicResource resource = requireResource(id);
        if (!resource.getCommunityId().equals(dto.getCommunityId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "资源不允许变更所属社区");
        }
        SecurityUtils.checkCommunityAccess(resource.getCommunityId());
        applyDto(resource, dto);
        resourceMapper.updateById(resource);
        return fillCommunityName(ResourceVO.from(resource));
    }

    /* 删除保护：存在待确认/已预约状态的预约时拒绝（软删除） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        PublicResource resource = requireResource(id);
        SecurityUtils.checkCommunityAccess(resource.getCommunityId());
        Long activeCount = reservationMapper.selectCount(new LambdaQueryWrapper<ResourceReservation>()
                .eq(ResourceReservation::getResourceId, id)
                .in(ResourceReservation::getStatus, ACTIVE_RESERVATION_STATUS));
        if (activeCount > 0) {
            throw new BusinessException(ErrorCode.RESOURCE_HAS_RESERVATION);
        }
        resourceMapper.softDeleteById(id);
        log.info("公共资源已删除：resourceId={}, operator={}", id, SecurityUtils.getUserId());
    }

    public ResourceVO getById(Long id) {
        return fillCommunityName(ResourceVO.from(requireResource(id)));
    }

    /** 社区下资源分页列表（公开；游客浏览；ADMIN 限绑定社区） */
    public PageVO<ResourceVO> pageByCommunity(Long communityId, long page, long size,
                                              String type, String status) {
        SecurityUtils.checkCommunityAccess(communityId);
        LambdaQueryWrapper<PublicResource> wrapper = new LambdaQueryWrapper<PublicResource>()
                .eq(PublicResource::getCommunityId, communityId)
                .eq(StringUtils.hasText(type), PublicResource::getType, type)
                .orderByAsc(PublicResource::getId);
        Page<PublicResource> result = resourceMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        PageVO<ResourceVO> vo = PageVO.of(result.convert(ResourceVO::from));
        vo.getRecords().forEach(this::fillCommunityName);
        return vo;
    }

    public PublicResource requireResource(Long id) {
        PublicResource resource = resourceMapper.selectById(id);
        if (resource == null) {
            throw new ResourceNotFoundException("公共资源不存在");
        }
        return resource;
    }

    private ResourceVO fillCommunityName(ResourceVO vo) {
        vo.setCommunityName(communityService.requireCommunity(vo.getCommunityId()).getName());
        return vo;
    }

    private void applyDto(PublicResource resource, CreateResourceDTO dto) {
        resource.setCommunityId(dto.getCommunityId());
        resource.setName(dto.getName());
        resource.setType(dto.getType());
        resource.setLocation(dto.getLocation());
        resource.setCapacity(dto.getCapacity());
        resource.setDescription(dto.getDescription());
    }
}
