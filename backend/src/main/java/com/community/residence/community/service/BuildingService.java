package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.BatchCreateBuildingsDTO;
import com.community.residence.community.dto.CreateBuildingDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.BatchCreateResultVO;
import com.community.residence.community.vo.BuildingVO;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** 楼栋业务逻辑：社区下四级结构的第二级，删除前校验单元引用；
 *  事务级联删除（cascade=true）与批量创建（D-端点1/2，50 阶段第三批） */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final HouseMapper houseMapper;
    private final HouseStatusHistoryMapper houseStatusHistoryMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
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

    /* 删除保护：楼栋下存在未删除单元时拒绝（软删除）；cascade=true 走级联删除 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DELETE", targetType = "BUILDING", targetId = "#id")
    public void delete(Long id, boolean cascade) {
        Building building = requireBuilding(id);
        SecurityUtils.checkCommunityAccess(building.getCommunityId());
        if (!cascade) {
            Long unitCount = unitMapper.selectCount(new LambdaQueryWrapper<Unit>()
                    .eq(Unit::getBuildingId, id));
            if (unitCount > 0) {
                throw new BusinessException(ErrorCode.BUILDING_REFERENCED);
            }
            buildingMapper.softDeleteById(id);
            log.info("楼栋已删除：buildingId={}, operator={}", id, SecurityUtils.getUserId());
            return;
        }
        cascadeDelete(building);
    }

    /**
     * 楼栋事务级联删除（D-端点1，50 阶段第三批）：参照社区级联（stage-50-1b
     * CommunityService.delete）事务模式，自底向上物理删除——房屋状态历史 → 房屋 →
     * 单元 → 楼栋。在住居民保护：任一房屋存在在住居住关系（move_out_date 为空）
     * 时整体拒绝（事务回滚），替代前端逐层编排的非原子删除。
     */
    private void cascadeDelete(Building building) {
        Long buildingId = building.getId();
        List<Unit> units = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getBuildingId, buildingId));
        List<Long> unitIds = units.stream().map(Unit::getId).toList();
        if (unitIds.isEmpty()) {
            buildingMapper.physicalDeleteById(buildingId);
            log.info("楼栋级联删除完成（空楼栋）：buildingId={}, operator={}",
                    buildingId, SecurityUtils.getUserId());
            return;
        }
        List<House> houses = houseMapper.selectList(new LambdaQueryWrapper<House>()
                .in(House::getUnitId, unitIds));
        List<Long> houseIds = houses.stream().map(House::getId).toList();
        if (!houseIds.isEmpty()) {
            Long livingCount = residenceRelationMapper.selectCount(
                    new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                            .in(com.community.residence.resident.entity.ResidenceRelation::getHouseId, houseIds)
                            .isNull(com.community.residence.resident.entity.ResidenceRelation::getMoveOutDate));
            if (livingCount > 0) {
                throw new BusinessException(ErrorCode.HOUSE_HAS_RESIDENT,
                        "楼栋下存在在住居民的房屋，不可级联删除（请先办理迁出）");
            }
            houseStatusHistoryMapper.delete(
                    new LambdaQueryWrapper<com.community.residence.community.entity.HouseStatusHistory>()
                            .in(com.community.residence.community.entity.HouseStatusHistory::getHouseId, houseIds));
            houseMapper.physicalDeleteByIds(houseIds);
        }
        unitMapper.physicalDeleteByIds(unitIds);
        buildingMapper.physicalDeleteById(buildingId);
        log.info("楼栋级联删除完成：buildingId={}, units={}, houses={}, operator={}",
                buildingId, unitIds.size(), houseIds.size(), SecurityUtils.getUserId());
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

    /**
     * 楼栋批量创建（D-端点2，50 阶段第三批）：部分成功语义对齐 R8 CSV 导入先例
     * （逐行反馈成功/失败原因，逐行独立不整体回滚），替代前端 310 请求循环单建。
     */
    public BatchCreateResultVO batchCreate(BatchCreateBuildingsDTO dto) {
        communityService.requireActiveCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        List<BatchCreateResultVO.Row> rows = new ArrayList<>();
        int success = 0;
        for (int i = 0; i < dto.getBuildings().size(); i++) {
            CreateBuildingDTO item = dto.getBuildings().get(i);
            try {
                Building building = new Building();
                building.setCommunityId(dto.getCommunityId());
                building.setName(item.getName());
                building.setFloors(item.getFloors());
                building.setDescription(item.getDescription());
                buildingMapper.insert(building);
                rows.add(BatchCreateResultVO.Row.success(i + 1, building.getId()));
                success++;
            } catch (Exception e) {
                rows.add(BatchCreateResultVO.Row.fail(i + 1, e.getMessage()));
            }
        }
        log.info("楼栋批量创建：communityId={}, total={}, success={}, operator={}",
                dto.getCommunityId(), dto.getBuildings().size(), success, SecurityUtils.getUserId());
        return BatchCreateResultVO.of(dto.getBuildings().size(), success, rows);
    }
}
