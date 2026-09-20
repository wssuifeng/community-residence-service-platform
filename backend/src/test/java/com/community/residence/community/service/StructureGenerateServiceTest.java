package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.dto.StructureBatchGenerateDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.vo.BatchCreateResultVO;
import com.community.residence.community.vo.StructureBatchGenerateResultVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构链一次性批量生成测试：上限保护、dryRun 零写入、楼栋重名部分成功、
 * 跳过项落地、跨层撞号去重、房屋行失败反馈、公共属性透传。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StructureGenerateService 结构链批量生成单元测试")
class StructureGenerateServiceTest {

    @Mock
    private CommunityService communityService;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private HouseService houseService;

    @InjectMocks
    private StructureGenerateService structureGenerateService;

    private MockedStatic<SecurityUtils> securityUtils;
    /** 房屋写入实参快照（每个单元一次），供字段透传断言 */
    private final List<List<CreateHouseDTO>> capturedHouses = new ArrayList<>();

    /* 楼栋重名查询用 LambdaWrapper 解析列名依赖实体元数据（纯 Mockito 环境需手动注册） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Building.class);
    }

    @BeforeEach
    void setUp() {
        securityUtils = mockStatic(SecurityUtils.class);
        Community community = new Community();
        community.setId(1L);
        community.setName("演示社区");
        community.setStatus("ACTIVE");
        lenient().when(communityService.requireActiveCommunity(1L)).thenReturn(community);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    /* ---- 上限保护：楼栋 ≤60、单元 ≤600、房屋 ≤5000（超限直接 INVALID_PARAM 并给出具体数字） ---- */

    @Test
    @DisplayName("上限保护：本次预计楼栋 61 栋 > 60 → INVALID_PARAM（提示含 61 与 60）")
    void generate_buildingOverLimit() {
        StructureBatchGenerateDTO dto = structureDto(1, 61);

        assertThatThrownBy(() -> structureGenerateService.generate(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("61")
                .hasMessageContaining("60");
        verify(buildingMapper, never()).insert(any(Building.class));
        verify(buildingMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("上限保护：本次预计单元 610 个 > 600 → INVALID_PARAM（提示含 610 与 600）")
    void generate_unitOverLimit() {
        StructureBatchGenerateDTO dto = structureDto(1, 10);
        dto.setUnitCountPerBuilding(61);

        assertThatThrownBy(() -> structureGenerateService.generate(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("610")
                .hasMessageContaining("600");
        verify(unitMapper, never()).insert(any(Unit.class));
    }

    @Test
    @DisplayName("上限保护：本次预计房屋 10800 套 > 5000 → INVALID_PARAM（提示含 10800 与 5000）")
    void generate_houseOverLimit() {
        StructureBatchGenerateDTO dto = houseDto(1, 6, 2, 30, 30);

        assertThatThrownBy(() -> structureGenerateService.generate(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("10800")
                .hasMessageContaining("5000");
        verify(buildingMapper, never()).insert(any(Building.class));
        verify(houseService, never()).insertHouses(any(), anyList(), anySet());
    }

    @Test
    @DisplayName("参数保护：楼栋结束序号小于起始序号 / 生成房屋未给建筑面积 → INVALID_PARAM")
    void generate_invalidSequenceAndMissingArea() {
        StructureBatchGenerateDTO reversed = structureDto(5, 3);
        assertThatThrownBy(() -> structureGenerateService.generate(reversed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束序号");

        StructureBatchGenerateDTO noArea = structureDto(1, 1);
        noArea.setUnitCountPerBuilding(1);
        noArea.setFloorsPerUnit(2);
        noArea.setHousesPerFloor(2);
        assertThatThrownBy(() -> structureGenerateService.generate(noArea))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("建筑面积");
    }

    /* ---- dryRun：零写入、只出预览与计数 ---- */

    @Test
    @DisplayName("dryRun 零写入：generate(dryRun=true) 与 preview 均不查库、不落库、不调用房屋写入")
    void dryRun_noWrites() {
        StructureBatchGenerateDTO dto = houseDto(1, 2, 1, 3, 4);
        dto.setDryRun(true);

        List<StructureBatchGenerateResultVO> results = List.of(
                structureGenerateService.generate(dto), structureGenerateService.preview(dto));

        for (StructureBatchGenerateResultVO vo : results) {
            assertThat(vo.getDryRun()).isTrue();
            assertThat(vo.getBuildingsCreated()).isEqualTo(2);
            assertThat(vo.getUnitsCreated()).isEqualTo(2);
            assertThat(vo.getHousesCreated()).isEqualTo(24);
            assertThat(vo.getPreviewBuildings()).containsExactly("1号楼", "2号楼");
            assertThat(vo.getPreviewUnits()).containsExactly("1号楼-1单元", "2号楼-1单元");
            assertThat(vo.getPreviewHouses()).hasSize(24);
            assertThat(vo.getPreviewHouses().get(0)).isEqualTo("1号楼-1单元-101");
            assertThat(vo.getSkippedCount()).isZero();
            assertThat(vo.getDedupedCount()).isZero();
            assertThat(vo.getFailures()).isEmpty();
        }
        verify(buildingMapper, never()).selectList(any());
        verify(buildingMapper, never()).insert(any(Building.class));
        verify(unitMapper, never()).insert(any(Unit.class));
        verify(houseService, never()).insertHouses(any(), anyList(), anySet());
    }

    @Test
    @DisplayName("dryRun 不做重名校验：楼栋名已存在仍计入预计数量，且不发起重名查询")
    void dryRun_ignoresNameConflicts() {
        StructureBatchGenerateDTO dto = structureDto(1, 2);
        dto.setDryRun(true);

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getBuildingsCreated()).isEqualTo(2);
        assertThat(vo.getUnitsCreated()).isEqualTo(2);
        assertThat(vo.getFailures()).isEmpty();
        verify(buildingMapper, never()).selectList(any());
    }

    /* ---- 楼栋重名：逐项 failure，其余照常创建 ---- */

    @Test
    @DisplayName("楼栋重名：已存在同名的 1号楼记 failure，2号楼/3号楼照常创建（不整批回滚）")
    void generate_duplicateBuildingName_partialSuccess() {
        StructureBatchGenerateDTO dto = structureDto(1, 3);
        when(buildingMapper.selectList(any())).thenReturn(List.of(existingBuilding("1号楼")));
        stubBuildingInsert(10L);
        stubUnitInsert(100L);

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getDryRun()).isFalse();
        assertThat(vo.getBuildingsCreated()).isEqualTo(2);
        assertThat(vo.getUnitsCreated()).isEqualTo(2);
        assertThat(vo.getFailures()).hasSize(1);
        assertThat(vo.getFailures().get(0).getLevel())
                .isEqualTo(StructureBatchGenerateResultVO.LEVEL_BUILDING);
        assertThat(vo.getFailures().get(0).getName()).isEqualTo("1号楼");
        assertThat(vo.getFailures().get(0).getReason()).contains("已存在同名楼栋");
        verify(buildingMapper, times(2)).insert(any(Building.class));
        assertThat(vo.getPreviewBuildings()).containsExactly("2号楼", "3号楼");
    }

    /* ---- 结构链落库：单元名后缀、房号规则、公共属性透传 ---- */

    @Test
    @DisplayName("结构链落库：单元名默认「单元」后缀、房号=房号前缀+楼层+两位序号、公共属性与状态透传")
    void generate_persistsWholeChain() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 2, 2, 3);
        dto.setHouseNumberPrefix("A-");
        dto.setRoomCount(3);
        dto.setDescription("批量生成");
        dto.setHouseStatus("RESERVED");
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getBuildingsCreated()).isEqualTo(1);
        assertThat(vo.getUnitsCreated()).isEqualTo(2);
        assertThat(vo.getHousesCreated()).isEqualTo(12);
        assertThat(vo.getPreviewUnits()).containsExactly("1号楼-1单元", "1号楼-2单元");
        assertThat(vo.getPreviewHouses()).hasSize(12);
        assertThat(vo.getPreviewHouses().get(0)).isEqualTo("1号楼-1单元-A-101");

        ArgumentCaptor<Unit> unitCaptor = ArgumentCaptor.forClass(Unit.class);
        verify(unitMapper, times(2)).insert(unitCaptor.capture());
        assertThat(unitCaptor.getAllValues()).extracting(Unit::getName)
                .containsExactly("1单元", "2单元");
        assertThat(unitCaptor.getAllValues().get(0).getCommunityId()).isEqualTo(1L);
        assertThat(unitCaptor.getAllValues().get(0).getBuildingId()).isEqualTo(10L);

        assertThat(capturedHouses).hasSize(2);
        List<CreateHouseDTO> firstUnitHouses = capturedHouses.get(0);
        assertThat(firstUnitHouses).extracting(CreateHouseDTO::getHouseNumber)
                .containsExactly("A-101", "A-102", "A-103", "A-201", "A-202", "A-203");
        assertThat(firstUnitHouses).extracting(CreateHouseDTO::getFloor)
                .containsExactly(1, 1, 1, 2, 2, 2);
        assertThat(firstUnitHouses.get(0).getUnitId()).isEqualTo(100L);
        assertThat(firstUnitHouses.get(0).getArea()).isEqualByComparingTo("80.5");
        assertThat(firstUnitHouses.get(0).getRoomCount()).isEqualTo(3);
        assertThat(firstUnitHouses.get(0).getDescription()).isEqualTo("批量生成");
        assertThat(firstUnitHouses.get(0).getStatus()).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("缺省值：房屋状态缺省 VACANT、房号前缀缺省为空、单元名后缀缺省「单元」")
    void generate_defaults() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 1, 2);
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        structureGenerateService.generate(dto);

        assertThat(capturedHouses).hasSize(1);
        assertThat(capturedHouses.get(0)).extracting(CreateHouseDTO::getHouseNumber)
                .containsExactly("101", "102");
        assertThat(capturedHouses.get(0).get(0).getStatus()).isEqualTo("VACANT");
        ArgumentCaptor<Unit> unitCaptor = ArgumentCaptor.forClass(Unit.class);
        verify(unitMapper).insert(unitCaptor.capture());
        assertThat(unitCaptor.getValue().getName()).isEqualTo("1单元");
    }

    /* ---- 跳过项与去重计数 ---- */

    @Test
    @DisplayName("跳过项落地：04 跳过每层 4 号（2 层 × 1 单元 = 跳过 2 套，生成 6 套）")
    void generate_skipItemsExcluded() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 2, 4);
        dto.setSkipItems("04");
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getSkippedCount()).isEqualTo(2);
        assertThat(vo.getDedupedCount()).isZero();
        assertThat(vo.getHousesCreated()).isEqualTo(6);
        assertThat(capturedHouses.get(0)).extracting(CreateHouseDTO::getHouseNumber)
                .containsExactly("101", "102", "103", "201", "202", "203");
    }

    @Test
    @DisplayName("跳过项落地：4:1 精确跳过第 4 层 1 号（8 套网格跳过 1 套，余 7 套）")
    void generate_spotSkipRule() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 4, 2);
        dto.setSkipItems("4:1");
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getSkippedCount()).isEqualTo(1);
        assertThat(vo.getHousesCreated()).isEqualTo(7);
        assertThat(capturedHouses.get(0)).extracting(CreateHouseDTO::getHouseNumber)
                .doesNotContain("401")
                .contains("402", "301");
    }

    @Test
    @DisplayName("跳过项落地：A-101 完整门牌号写法（含房号前缀，仅跳过该套）")
    void generate_fullNumberSkipRule() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 2, 3);
        dto.setHouseNumberPrefix("A-");
        dto.setSkipItems("A-101");
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getSkippedCount()).isEqualTo(1);
        assertThat(capturedHouses.get(0)).extracting(CreateHouseDTO::getHouseNumber)
                .containsExactly("A-102", "A-103", "A-201", "A-202", "A-203");
    }

    @Test
    @DisplayName("跨层撞号去重：补零宽度不足时同单元撞号只保留首次出现（dedupedCount 计数）")
    void generate_crossFloorCollisionDeduped() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 11, 11);
        dto.setHouseNumberWidth(1);
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        stubHouseInsertAllSuccess();

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        /* 「1 层 11 号」与「11 层 1 号」在宽度 1 下同为 111，后者被去重剔除 */
        assertThat(vo.getDedupedCount()).isEqualTo(1);
        assertThat(vo.getHousesCreated()).isEqualTo(120);
    }

    /* ---- 房屋行失败：逐行反馈 ---- */

    @Test
    @DisplayName("房屋行失败逐行反馈：失败行映射为 level=HOUSE 的 failure，成功行照常计入")
    void generate_houseRowFailureReported() {
        StructureBatchGenerateDTO dto = houseDto(1, 1, 1, 1, 2);
        when(buildingMapper.selectList(any())).thenReturn(List.of());
        stubBuildingInsert(10L);
        stubUnitInsert(100L);
        when(houseService.insertHouses(any(Unit.class), anyList(), anySet())).thenAnswer(inv -> {
            List<CreateHouseDTO> items = inv.getArgument(1);
            List<BatchCreateResultVO.Row> rows = new ArrayList<>();
            rows.add(BatchCreateResultVO.Row.success(1, 501L));
            rows.add(BatchCreateResultVO.Row.fail(2, "该单元下房号已存在：102"));
            return BatchCreateResultVO.of(items.size(), 1, rows);
        });

        StructureBatchGenerateResultVO vo = structureGenerateService.generate(dto);

        assertThat(vo.getHousesCreated()).isEqualTo(1);
        assertThat(vo.getFailures()).hasSize(1);
        assertThat(vo.getFailures().get(0).getLevel())
                .isEqualTo(StructureBatchGenerateResultVO.LEVEL_HOUSE);
        assertThat(vo.getFailures().get(0).getName()).isEqualTo("1号楼-1单元-102");
        assertThat(vo.getFailures().get(0).getReason()).contains("房号已存在");
    }

    /* ---- 脚手架 ---- */

    /** 仅生成楼栋与单元的结构请求 */
    private StructureBatchGenerateDTO structureDto(int startNo, int endNo) {
        StructureBatchGenerateDTO dto = new StructureBatchGenerateDTO();
        dto.setCommunityId(1L);
        dto.setBuildingStartNo(startNo);
        dto.setBuildingEndNo(endNo);
        dto.setBuildingNameSuffix("号楼");
        dto.setUnitCountPerBuilding(1);
        return dto;
    }

    /** 完整结构链请求：楼栋 start~end + 每栋 unitPerBuilding 个单元 × floors 层 × perFloor 套 */
    private StructureBatchGenerateDTO houseDto(int startNo, int endNo, int unitPerBuilding,
                                               int floors, int perFloor) {
        StructureBatchGenerateDTO dto = structureDto(startNo, endNo);
        dto.setUnitCountPerBuilding(unitPerBuilding);
        dto.setFloorsPerUnit(floors);
        dto.setHousesPerFloor(perFloor);
        dto.setArea(new BigDecimal("80.5"));
        return dto;
    }

    private Building existingBuilding(String name) {
        Building building = new Building();
        building.setId(9L);
        building.setCommunityId(1L);
        building.setName(name);
        return building;
    }

    /** 依次分配自增主键（模拟 AUTO 回填；末位 ID 复用给剩余行） */
    private void stubBuildingInsert(Long... ids) {
        AtomicInteger counter = new AtomicInteger();
        when(buildingMapper.insert(any(Building.class))).thenAnswer(inv -> {
            Building building = inv.getArgument(0);
            building.setId(ids[Math.min(counter.getAndIncrement(), ids.length - 1)]);
            return 1;
        });
    }

    private void stubUnitInsert(Long... ids) {
        AtomicInteger counter = new AtomicInteger();
        when(unitMapper.insert(any(Unit.class))).thenAnswer(inv -> {
            Unit unit = inv.getArgument(0);
            unit.setId(ids[Math.min(counter.getAndIncrement(), ids.length - 1)]);
            return 1;
        });
    }

    /** 房屋写入全部成功，并把每次实参快照留作断言素材 */
    private void stubHouseInsertAllSuccess() {
        when(houseService.insertHouses(any(Unit.class), anyList(), anySet())).thenAnswer(inv -> {
            List<CreateHouseDTO> items = inv.getArgument(1);
            capturedHouses.add(new ArrayList<>(items));
            List<BatchCreateResultVO.Row> rows = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                rows.add(BatchCreateResultVO.Row.success(i + 1, 500L + i));
            }
            return BatchCreateResultVO.of(items.size(), items.size(), rows);
        });
    }
}
