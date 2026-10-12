package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.dto.BatchHouseLayoutDTO;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.log.annotation.OperationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 房屋户型是源头；单屋编辑与多选修改同步关联挂牌。 */
@Service
@RequiredArgsConstructor
public class HouseLayoutService {

    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final HousingMapper housingMapper;
    private final BuildingBoundaryService buildingBoundaryService;

    public void synchronize(Long houseId, String layout) {
        housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                .eq(Housing::getHouseId, houseId).set(Housing::getLayout, layout));
    }

    /* 先验证完整选区，再按父楼栋/房屋ID统一加锁；任一非法项不允许部分写入。 */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(operationType = "UPDATE", targetType = "HOUSE", content = "'批量配置房屋户型'")
    public int update(BatchHouseLayoutDTO dto) {
        List<Long> ids = dto.getHouseIds().stream().distinct().sorted().toList();
        List<House> houses = houseMapper.selectBatchIds(ids);
        if (houses.size() != ids.size()) {
            throw new ResourceNotFoundException("所选房屋不存在或无权访问，请刷新选区");
        }
        houses.forEach(house -> SecurityUtils.checkCommunityAccess(house.getCommunityId()));
        List<Long> unitIds = houses.stream().map(House::getUnitId).distinct().toList();
        List<Unit> units = unitMapper.selectBatchIds(unitIds);
        if (units.size() != unitIds.size()) {
            throw new ResourceNotFoundException("所选房屋的单元不存在");
        }
        units.stream().map(Unit::getBuildingId).distinct().sorted()
                .forEach(buildingBoundaryService::lockBuilding);
        for (Long id : ids) {
            House house = houseMapper.selectByIdForUpdate(id);
            if (house == null) {
                throw new ResourceNotFoundException("所选房屋已删除，请刷新选区");
            }
            SecurityUtils.checkCommunityAccess(house.getCommunityId());
        }
        String layout = dto.getLayout().trim();
        houseMapper.update(null, new LambdaUpdateWrapper<House>()
                .in(House::getId, ids).set(House::getLayout, layout));
        housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                .in(Housing::getHouseId, ids).set(Housing::getLayout, layout));
        return ids.size();
    }
}
