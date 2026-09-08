package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.dto.UpdateHouseStatusDTO;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.HouseStatusHistory;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.HouseStatusHistoryVO;
import com.community.residence.community.vo.HouseVO;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 房屋业务逻辑：四级结构末级；状态变更强制留痕（house_status_history） */
@Slf4j
@Service
@RequiredArgsConstructor
public class HouseService {

    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final HouseStatusHistoryMapper historyMapper;
    private final ResidenceRelationMapper residenceRelationMapper;

    @Transactional(rollbackFor = Exception.class)
    public HouseVO create(CreateHouseDTO dto) {
        Unit unit = requireUnit(dto.getUnitId());
        SecurityUtils.checkCommunityAccess(unit.getCommunityId());
        House house = new House();
        house.setUnitId(unit.getId());
        house.setCommunityId(unit.getCommunityId());
        applyDto(house, dto);
        house.setStatus(StringUtils.hasText(dto.getStatus()) ? dto.getStatus() : HouseStatusConstant.VACANT);
        houseMapper.insert(house);
        return HouseVO.from(house);
    }

    @Transactional(rollbackFor = Exception.class)
    public HouseVO update(Long id, CreateHouseDTO dto) {
        House house = requireHouse(id);
        if (!house.getUnitId().equals(dto.getUnitId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "房屋不允许变更所属单元");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        applyDto(house, dto);
        houseMapper.updateById(house);
        return HouseVO.from(house);
    }

    /* 删除保护：存在在住居民（move_out_date 为空）时拒绝；软删除 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        House house = requireHouse(id);
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        Long livingCount = residenceRelationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, id)
                .isNull(ResidenceRelation::getMoveOutDate));
        if (livingCount > 0) {
            throw new BusinessException(ErrorCode.HOUSE_HAS_RESIDENT);
        }
        houseMapper.softDeleteById(id);
        log.info("房屋已删除：houseId={}, operator={}", id, SecurityUtils.getUserId());
    }

    public HouseVO getById(Long id) {
        House house = requireHouse(id);
        HouseVO vo = HouseVO.from(house);
        Unit unit = unitMapper.selectById(house.getUnitId());
        if (unit != null) {
            vo.setUnitName(unit.getName());
        }
        return vo;
    }

    /** 单元下房屋分页列表（公开；ADMIN 限绑定社区） */
    public PageVO<HouseVO> pageByUnit(Long unitId, long page, long size, String status) {
        Unit unit = requireUnit(unitId);
        SecurityUtils.checkCommunityAccess(unit.getCommunityId());
        LambdaQueryWrapper<House> wrapper = new LambdaQueryWrapper<House>()
                .eq(House::getUnitId, unitId)
                .eq(StringUtils.hasText(status), House::getStatus, status)
                .orderByAsc(House::getId);
        Page<House> result = houseMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(HouseVO::from));
    }

    /* 状态变更：写主表 + 追加历史记录（同一事务） */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, UpdateHouseStatusDTO dto) {
        House house = requireHouse(id);
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        String oldStatus = house.getStatus();
        if (oldStatus.equals(dto.getStatus())) {
            return;
        }
        house.setStatus(dto.getStatus());
        houseMapper.updateById(house);

        HouseStatusHistory history = new HouseStatusHistory();
        history.setHouseId(id);
        history.setCommunityId(house.getCommunityId());
        history.setOldStatus(oldStatus);
        history.setNewStatus(dto.getStatus());
        history.setOperatorId(SecurityUtils.getUserId());
        history.setRemark(dto.getRemark());
        historyMapper.insert(history);
        log.info("房屋状态变更：houseId={}, {} -> {}, operator={}", id, oldStatus, dto.getStatus(),
                SecurityUtils.getUserId());
    }

    /** 房屋状态变更历史分页 */
    public PageVO<HouseStatusHistoryVO> statusHistory(Long id, long page, long size) {
        requireHouse(id);
        LambdaQueryWrapper<HouseStatusHistory> wrapper = new LambdaQueryWrapper<HouseStatusHistory>()
                .eq(HouseStatusHistory::getHouseId, id)
                .orderByDesc(HouseStatusHistory::getId);
        Page<HouseStatusHistory> result = historyMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(HouseStatusHistoryVO::from));
    }

    public House requireHouse(Long id) {
        House house = houseMapper.selectById(id);
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        return house;
    }

    private void applyDto(House house, CreateHouseDTO dto) {
        house.setHouseNumber(dto.getHouseNumber());
        house.setFloor(dto.getFloor());
        house.setArea(dto.getArea());
        house.setRoomCount(dto.getRoomCount());
        house.setLayout(dto.getLayout());
        house.setOrientation(dto.getOrientation());
        if (StringUtils.hasText(dto.getStatus())) {
            house.setStatus(dto.getStatus());
        }
        house.setDescription(dto.getDescription());
    }

    private Unit requireUnit(Long id) {
        Unit unit = unitMapper.selectById(id);
        if (unit == null) {
            throw new ResourceNotFoundException("单元不存在");
        }
        return unit;
    }
}
