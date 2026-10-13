package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.BatchHouseAttributesDTO;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.log.annotation.OperationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Set;

/** 房屋属性原子编辑，共享户型源头与楼栋边界约束。 */
@Service
@RequiredArgsConstructor
public class HouseAttributesService {
    private static final Set<String> ALLOWED = Set.of("houseNumber", "floor", "area", "layout", "orientation", "description");
    private final HouseLayoutService houseLayoutService;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingBoundaryService boundary;

    /* 先完整验证字段及选区，按统一锁序验证每栋楼层与房号唯一，再写入；异常整体回滚。 */
    @Transactional(rollbackFor=Exception.class)
    @OperationLog(operationType="UPDATE", targetType="HOUSE", content="'编辑所选房屋自身属性'")
    public int update(BatchHouseAttributesDTO dto) {
        Set<String> fields = dto.getFields();
        if (fields == null || fields.isEmpty() || !ALLOWED.containsAll(fields)) invalid("请选择合法的房屋自身属性");
        if (fields.contains("floor") && dto.getFloor() == null) invalid("楼层不能为空");
        if (fields.contains("area") && dto.getArea() == null) invalid("面积不能为空");
        if (fields.contains("houseNumber") && (!StringUtils.hasText(dto.getHouseNumber()) || dto.getHouseIds().stream().distinct().count() != 1))
            invalid("房号不能为空，且只能在单选时修改");
        List<House> houses = houseLayoutService.lockSelection(dto.getHouseIds());
        for (House house : houses) {
            if (fields.contains("floor")) {
                Unit unit = unitMapper.selectById(house.getUnitId());
                if (unit == null) invalid("房屋的单元不存在，请刷新选区");
                boundary.validateFloor(unit, dto.getFloor());
            }
            if (fields.contains("houseNumber") && houseMapper.selectCount(new LambdaQueryWrapper<House>()
                    .eq(House::getUnitId, house.getUnitId()).eq(House::getHouseNumber, dto.getHouseNumber().trim())
                    .ne(House::getId, house.getId())) > 0) invalid("同一单元房号不能重复");
        }
        try {
            for (House house : houses) {
                LambdaUpdateWrapper<House> patch = new LambdaUpdateWrapper<House>().eq(House::getId, house.getId());
                if (fields.contains("houseNumber")) patch.set(House::getHouseNumber, dto.getHouseNumber().trim());
                if (fields.contains("floor")) patch.set(House::getFloor, dto.getFloor());
                if (fields.contains("area")) patch.set(House::getArea, dto.getArea());
                if (fields.contains("layout")) patch.set(House::getLayout, clean(dto.getLayout()));
                if (fields.contains("orientation")) patch.set(House::getOrientation, clean(dto.getOrientation()));
                if (fields.contains("description")) patch.set(House::getDescription, clean(dto.getDescription()));
                houseMapper.update(null, patch);
                if (fields.contains("layout")) houseLayoutService.synchronize(house.getId(), clean(dto.getLayout()));
            }
        } catch (DuplicateKeyException conflict) { invalid("同一单元房号不能重复，请刷新后重试"); }
        return houses.size();
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_PARAM, message); }
}
