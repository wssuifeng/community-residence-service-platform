package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateBuildingDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.BuildingVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 楼栋业务逻辑：社区下四级结构的第二级，删除前校验单元引用 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final CommunityService communityService;

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "BUILDING", targetId = "#result.id", communityId = "#dto.communityId", content = "'创建楼栋：' + #dto.name")
    public BuildingVO create(CreateBuildingDTO dto) {
        communityService.requireActiveCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        Building building = new Building();
        building.setCommunityId(dto.getCommunityId());
        building.setName(dto.getName());
        building.setFloors(dto.getFloors());
        building.setDescription(dto.getDescription());
        buildingMapper.insert(building);
        return BuildingVO.from(building);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "BUILDING", targetId = "#id", content = "'更新楼栋：' + #dto.name")
    public BuildingVO update(Long id, CreateBuildingDTO dto) {
        Building building = requireBuilding(id);
        /* 更新不允许跨社区迁移楼栋（四级结构按社区归属建立，迁移会导致子级引用断裂） */
        if (!building.getCommunityId().equals(dto.getCommunityId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "楼栋不允许变更所属社区");
        }
        SecurityUtils.checkCommunityAccess(building.getCommunityId());
        building.setName(dto.getName());
        building.setFloors(dto.getFloors());
        building.setDescription(dto.getDescription());
        buildingMapper.updateById(building);
        return BuildingVO.from(building);
    }

    /* 删除保护：楼栋下存在未删除单元时拒绝（软删除） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DELETE", targetType = "BUILDING", targetId = "#id")
    public void delete(Long id) {
        Building building = requireBuilding(id);
        SecurityUtils.checkCommunityAccess(building.getCommunityId());
        Long unitCount = unitMapper.selectCount(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, id));
        if (unitCount > 0) {
            throw new BusinessException(ErrorCode.BUILDING_REFERENCED);
        }
        buildingMapper.softDeleteById(id);
        log.info("楼栋已删除：buildingId={}, operator={}", id, SecurityUtils.getUserId());
    }

    public BuildingVO getById(Long id) {
        Building building = requireBuilding(id);
        BuildingVO vo = BuildingVO.from(building);
        vo.setCommunityName(communityService.requireCommunity(building.getCommunityId()).getName());
        return vo;
    }

    /** 社区下楼栋分页列表（公开；ADMIN 限绑定社区） */
    public PageVO<BuildingVO> pageByCommunity(Long communityId, long page, long size, String keyword) {
        SecurityUtils.checkCommunityAccess(communityId);
        LambdaQueryWrapper<Building> wrapper = new LambdaQueryWrapper<Building>()
                .eq(Building::getCommunityId, communityId)
                .like(StringUtils.hasText(keyword), Building::getName, keyword)
                .orderByAsc(Building::getId);
        Page<Building> result = buildingMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(BuildingVO::from));
    }

    public Building requireBuilding(Long id) {
        Building building = buildingMapper.selectById(id);
        if (building == null) {
            throw new ResourceNotFoundException("楼栋不存在");
        }
        return building;
    }
}
