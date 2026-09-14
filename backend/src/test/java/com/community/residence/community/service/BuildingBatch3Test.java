package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.BatchCreateBuildingsDTO;
import com.community.residence.community.dto.BatchCreateHousesDTO;
import com.community.residence.community.dto.BatchCreateUnitsDTO;
import com.community.residence.community.dto.CreateBuildingDTO;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.BatchCreateResultVO;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第三批 D-端点回归：楼栋事务级联删除（D-端点1）+ 楼栋/单元/房屋批量创建（D-端点2） */
@ExtendWith(MockitoExtension.class)
@DisplayName("社区结构第三批端点回归（D-端点1/2）")
class BuildingBatch3Test {

    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private HouseStatusHistoryMapper houseStatusHistoryMapper;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private CommunityService communityService;

    @InjectMocks
    private BuildingService buildingService;
    @InjectMocks
    private UnitService unitService;
    @InjectMocks
    private HouseService houseService;

    private Building building;
    private Unit unit;
    private House house;

    @BeforeEach
    void setUp() {
        building = new Building();
        building.setId(1L);
        building.setCommunityId(1L);
        building.setName("1栋");

        unit = new Unit();
        unit.setId(11L);
        unit.setBuildingId(1L);
        unit.setCommunityId(1L);
        unit.setName("一单元");

        house = new House();
        house.setId(101L);
        house.setUnitId(11L);
        house.setCommunityId(1L);
        house.setHouseNumber("101");
        house.setStatus("VACANT");
    }

    /* ---- D-端点1：楼栋级联删除 ---- */

    @Test
    @DisplayName("D-端点1：默认 cascade=false 维持现状——有单元拒删 5102")
    void delete_default_noCascade_rejects() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectCount(any())).thenReturn(2L);

            assertThatThrownBy(() -> buildingService.delete(1L, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.BUILDING_REFERENCED));
        }
    }

    @Test
    @DisplayName("D-端点1：cascade=true 自底向上物理删除——历史→房屋→单元→楼栋")
    void delete_cascade_physicalBottomUp() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectList(any())).thenReturn(List.of(unit));
            when(houseMapper.selectList(any())).thenReturn(List.of(house));
            when(residenceRelationMapper.selectCount(any())).thenReturn(0L);

            buildingService.delete(1L, true);

            /* 子表在前、父表在后（FK 约束顺序） */
            var inOrder = org.mockito.Mockito.inOrder(
                    houseStatusHistoryMapper, houseMapper, unitMapper, buildingMapper);
            inOrder.verify(houseStatusHistoryMapper).delete(any());
            inOrder.verify(houseMapper).physicalDeleteByIds(List.of(101L));
            inOrder.verify(unitMapper).physicalDeleteByIds(List.of(11L));
            inOrder.verify(buildingMapper).physicalDeleteById(1L);
        }
    }

    @Test
    @DisplayName("D-端点1：级联删除在住居民保护——存在在住关系整体拒绝（事务回滚）")
    void delete_cascade_livingResidents_rejects() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectList(any())).thenReturn(List.of(unit));
            when(houseMapper.selectList(any())).thenReturn(List.of(house));
            when(residenceRelationMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> buildingService.delete(1L, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.HOUSE_HAS_RESIDENT));
            verify(houseMapper, never()).physicalDeleteByIds(anyList());
            verify(buildingMapper, never()).physicalDeleteById(any());
        }
    }

    @Test
    @DisplayName("D-端点1：空楼栋 cascade 直接物理删楼栋")
    void delete_cascade_emptyBuilding() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectList(any())).thenReturn(List.of());

            buildingService.delete(1L, true);

            verify(buildingMapper).physicalDeleteById(1L);
            verify(houseMapper, never()).physicalDeleteByIds(anyList());
        }
    }

    /* ---- D-端点2：批量创建（部分成功语义） ---- */

    @Test
    @DisplayName("D-端点2：楼栋批量创建——第 2 行失败不阻断，逐行反馈")
    void batchCreateBuildings_partialSuccess() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            lenient().when(communityService.requireActiveCommunity(1L)).thenReturn(null);
            var dto = new BatchCreateBuildingsDTO();
            dto.setCommunityId(1L);
            var ok = buildingDto("2栋");
            var bad = buildingDto(null); // @NotBlank 在 Controller 层，服务层以异常模拟失败
            dto.setBuildings(List.of(ok, bad));
            when(buildingMapper.insert(any(Building.class)))
                    .thenAnswer(inv -> {
                        inv.getArgument(0, Building.class).setId(50L);
                        return 1;
                    })
                    .thenThrow(new RuntimeException("楼栋名称不能为空"));

            BatchCreateResultVO vo = buildingService.batchCreate(dto);

            assertThat(vo.getTotal()).isEqualTo(2);
            assertThat(vo.getSuccess()).isEqualTo(1);
            assertThat(vo.getFail()).isEqualTo(1);
            assertThat(vo.getRows().get(0).getSuccess()).isTrue();
            assertThat(vo.getRows().get(0).getId()).isEqualTo(50L);
            assertThat(vo.getRows().get(1).getSuccess()).isFalse();
            assertThat(vo.getRows().get(1).getReason()).contains("楼栋名称");
        }
    }

    @Test
    @DisplayName("D-端点2：单元批量创建——社区ID 由楼栋推导冗余")
    void batchCreateUnits_communityDerived() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            var dto = new BatchCreateUnitsDTO();
            dto.setBuildingId(1L);
            dto.setNames(List.of("一单元", "二单元"));
            when(unitMapper.insert(any(Unit.class))).thenAnswer(inv -> {
                inv.getArgument(0, Unit.class).setId(60L);
                return 1;
            });

            BatchCreateResultVO vo = unitService.batchCreate(dto);

            assertThat(vo.getSuccess()).isEqualTo(2);
            org.mockito.ArgumentCaptor<Unit> captor =
                    org.mockito.ArgumentCaptor.forClass(Unit.class);
            verify(unitMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
            assertThat(captor.getAllValues().get(0).getCommunityId()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("D-端点2：房屋批量创建——area 必填缺失行失败，其余成功")
    void batchCreateHouses_partialSuccess() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> ignored =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(unitMapper.selectById(11L)).thenReturn(unit);
            var dto = new BatchCreateHousesDTO();
            dto.setUnitId(11L);
            var ok = houseDto("101", new BigDecimal("80.5"));
            var bad = houseDto("102", null); // area @NotNull 在 Controller 层，服务层以异常模拟
            dto.setHouses(List.of(ok, bad));
            when(houseMapper.insert(any(House.class)))
                    .thenAnswer(inv -> {
                        inv.getArgument(0, House.class).setId(70L);
                        return 1;
                    })
                    .thenThrow(new RuntimeException("建筑面积不能为空"));

            BatchCreateResultVO vo = houseService.batchCreate(dto);

            assertThat(vo.getSuccess()).isEqualTo(1);
            assertThat(vo.getFail()).isEqualTo(1);
            assertThat(vo.getRows().get(0).getId()).isEqualTo(70L);
            assertThat(vo.getRows().get(1).getReason()).contains("建筑面积");
        }
    }

    /* ---- 脚手架 ---- */

    private CreateBuildingDTO buildingDto(String name) {
        var dto = new CreateBuildingDTO();
        dto.setCommunityId(1L);
        dto.setName(name);
        dto.setFloors(6);
        return dto;
    }

    private CreateHouseDTO houseDto(String number, BigDecimal area) {
        var dto = new CreateHouseDTO();
        dto.setUnitId(11L);
        dto.setHouseNumber(number);
        dto.setFloor(1);
        dto.setArea(area);
        return dto;
    }
}
