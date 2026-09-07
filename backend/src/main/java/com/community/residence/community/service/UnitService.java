package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateUnitDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.UnitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单元业务逻辑：社区下四级结构的第三级，社区ID 由楼栋推导冗余存储 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnitService {

    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final HouseMapper houseMapper;

    @Transactional(rollbackFor = Exception.class)
    public UnitVO create(CreateUnitDTO dto) {
        Building building = requireBuilding(dto.getBuildingId());
        SecurityUtils.checkCommunityAccess(building.getCommunityId());
        Unit unit = new Unit();
        unit.setBuildingId(building.getId());
        unit.setCommunityId(building.getCommunityId());
        unit.setName(dto.getName());
        unit.setDescription(dto.getDescription());
        unitMapper.insert(unit);
        return UnitVO.from(unit);
    }

    @Transactional(rollbackFor = Exception.class)
    public UnitVO update(Long id, CreateUnitDTO dto) {
        Unit unit = requireUnit(id);
        Building building = requireBuilding(dto.getBuildingId());
        if (!unit.getBuildingId().equals(dto.getBuildingId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "单元不允许变更所属楼栋");
        }
        SecurityUtils.checkCommunityAccess(building.getCommunityId());
        unit.setName(dto.getName());
        unit.setDescription(dto.getDescription());
        unitMapper.updateById(unit);
        return UnitVO.from(unit);
    }

    /* 删除保护：单元下存在未删除房屋时拒绝（软删除） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Unit unit = requireUnit(id);
        SecurityUtils.checkCommunityAccess(unit.getCommunityId());
        Long houseCount = houseMapper.selectCount(new LambdaQueryWrapper<House>()
                .eq(House::getUnitId, id));
        if (houseCount > 0) {
            throw new BusinessException(ErrorCode.UNIT_REFERENCED);
        }
        unitMapper.softDeleteById(id);
        log.info("单元已删除：unitId={}, operator={}", id, SecurityUtils.getUserId());
    }

    public UnitVO getById(Long id) {
        Unit unit = requireUnit(id);
        UnitVO vo = UnitVO.from(unit);
        Building building = buildingMapper.selectById(unit.getBuildingId());
        if (building != null) {
            vo.setBuildingName(building.getName());
        }
        return vo;
    }

    /** 楼栋下单元分页列表（公开） */
    public PageVO<UnitVO> pageByBuilding(Long buildingId, long page, long size) {
        LambdaQueryWrapper<Unit> wrapper = new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, buildingId)
                .orderByAsc(Unit::getId);
        Page<Unit> result = unitMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(UnitVO::from));
    }

    public Unit requireUnit(Long id) {
        Unit unit = unitMapper.selectById(id);
        if (unit == null) {
            throw new ResourceNotFoundException("单元不存在");
        }
        return unit;
    }

    private Building requireBuilding(Long id) {
        Building building = buildingMapper.selectById(id);
        if (building == null) {
            throw new ResourceNotFoundException("楼栋不存在");
        }
        return building;
    }
}
