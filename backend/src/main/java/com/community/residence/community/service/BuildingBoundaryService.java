package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 单栋楼层边界：所有写入口在事务内锁同一父记录后校验。 */
@Service
@RequiredArgsConstructor
public class BuildingBoundaryService {

    private final BuildingMapper buildingMapper;
    private final HouseMapper houseMapper;

    public Building lockBuilding(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "单元未关联楼栋");
        }
        Building building = buildingMapper.selectByIdForUpdate(id);
        if (building == null) {
            throw new ResourceNotFoundException("楼栋不存在");
        }
        return building;
    }

    public void validateFloor(Unit unit, Integer floor) {
        Building building = lockBuilding(unit.getBuildingId());
        if (!building.getCommunityId().equals(unit.getCommunityId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "单元与楼栋社区不一致");
        }
        validateLockedFloor(building, floor);
    }

    public void validateLockedFloor(Building building, Integer floor) {
        if (building.getFloors() == null || building.getFloors() < 1
                || floor == null || floor < 1 || floor > building.getFloors()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "该楼栋共" + building.getFloors() + "层，房屋楼层必须在1～" + building.getFloors() + "层之间");
        }
    }

    /* 增层可直接保存；降层以锁定读的现有房屋为准，不自动删除房屋。 */
    public void validateReduction(Long id, Integer floors) {
        Building building = lockBuilding(id);
        if (floors == null || floors < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "楼栋层数必须为正整数");
        }
        if (building.getFloors() != null && floors >= building.getFloors()) {
            return;
        }
        Integer highest = houseMapper.selectHighestFloorByBuilding(id);
        if (highest != null && highest > floors) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "现有房屋最高位于第" + highest + "层，请先处理超出新上限的房屋再降低楼栋层数");
        }
    }
}
