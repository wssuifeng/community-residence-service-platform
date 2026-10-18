package com.community.residence.common.community.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.community.dto.BatchHouseLayoutDTO;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.Unit;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.log.annotation.OperationLog;
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
        List<House> houses = lockSelection(dto.getHouseIds());
        List<Long> ids = houses.stream().map(House::getId).toList();
        String layout = dto.getLayout().trim();
        houseMapper.update(null, new LambdaUpdateWrapper<House>()
                .in(House::getId, ids).set(House::getLayout, layout));
        housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                .in(Housing::getHouseId, ids).set(Housing::getLayout, layout));
        return ids.size();
    }
    /* 调用方必须已有事务；整区验证后父楼栋/房屋按ID加锁，供多属性编辑共用。 */
    public List<House> lockSelection(List<Long> houseIds) {
        List<Long> ids = houseIds.stream().distinct().sorted().toList();
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
        java.util.ArrayList<House> locked = new java.util.ArrayList<>();
        for (Long id : ids) {
            House house = houseMapper.selectByIdForUpdate(id);
            if (house == null) {
                throw new ResourceNotFoundException("所选房屋已删除，请刷新选区");
            }
            SecurityUtils.checkCommunityAccess(house.getCommunityId());
            locked.add(house);
        }
        return locked;
    }

}
