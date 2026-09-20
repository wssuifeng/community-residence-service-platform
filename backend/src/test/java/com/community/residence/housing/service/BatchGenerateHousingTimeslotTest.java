package com.community.residence.housing.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.housing.dto.BatchGenerateHousingDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.vo.BatchGenerateResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 批量挂牌同时建立可预约看房时段（接口设计.md 9.15.1）：
 * 默认勾选工作日两段（09:00-12:00 / 14:00-18:00）、CUSTOM 自定义、
 * createTimeslots=false 不建、跳过的房屋不建、同房源同起止幂等跳过、越界 400。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("批量挂牌建立看房时段（9.15.1）")
class BatchGenerateHousingTimeslotTest {

    @Mock
    private CommunityService communityService;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;

    @InjectMocks
    private HousingService housingService;

    private House house1;
    private House house2;

    @BeforeEach
    void setUp() {
        Building building = new Building();
        building.setId(100L);
        building.setCommunityId(1L);
        building.setName("1 号楼");

        Unit unit1 = new Unit();
        unit1.setId(200L);
        unit1.setCommunityId(1L);
        unit1.setBuildingId(100L);
        unit1.setName("1 单元");

        house1 = new House();
        house1.setId(1000L);
        house1.setCommunityId(1L);
        house1.setUnitId(200L);
        house1.setHouseNumber("101");
        house2 = new House();
        house2.setId(1001L);
        house2.setCommunityId(1L);
        house2.setUnitId(200L);
        house2.setHouseNumber("102");

        lenient().when(houseMapper.selectList(any())).thenReturn(List.of(house1, house2));
        lenient().when(unitMapper.selectList(any())).thenReturn(List.of(unit1));
        lenient().when(buildingMapper.selectList(any())).thenReturn(List.of(building));
        /* 无在架房源（首查）；时段去重查询返回空表 */
        lenient().when(housingMapper.selectList(any())).thenReturn(List.of());
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of());
        /* 插入房源时回填主键（MyBatis-Plus IdType.AUTO 行为） */
        lenient().when(housingMapper.insert(any(Housing.class))).thenAnswer(inv -> {
            Housing inserted = inv.getArgument(0);
            inserted.setId(9000L + inserted.getHouseId());
            return 1;
        });
    }

    private BatchGenerateHousingDTO baseDto() {
        BatchGenerateHousingDTO dto = new BatchGenerateHousingDTO();
        dto.setCommunityId(1L);
        dto.setMonthlyRent(new BigDecimal("2000"));
        return dto;
    }

    private void runAsAdmin(BatchGenerateHousingDTO dto) {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            housingService.batchGenerate(dto);
        }
    }

    private BatchGenerateResultVO runAsAdminAndGet(BatchGenerateHousingDTO dto) {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            return housingService.batchGenerate(dto);
        }
    }

    @Test
    @DisplayName("默认口径：未传 createTimeslots 视为 true，每套新房源建工作日两段 ×5 天 = 10 条")
    void batchGenerate_defaultTimeslots_created() {
        BatchGenerateResultVO result = runAsAdminAndGet(baseDto());

        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getTimeslotsCreated()).isEqualTo(20);
        ArgumentCaptor<HousingTimeslot> captor = ArgumentCaptor.forClass(HousingTimeslot.class);
        verify(timeslotMapper, times(20)).insert(captor.capture());
        List<HousingTimeslot> slots = captor.getAllValues();
        assertThat(slots).allSatisfy(s -> {
            assertThat(s.getIsAvailable()).isEqualTo(1);
            assertThat(s.getDayOfWeek()).isBetween(1, 5);
            assertThat(s.getStartTime()).isIn(LocalTime.of(9, 0), LocalTime.of(14, 0));
            assertThat(s.getEndTime()).isIn(LocalTime.of(12, 0), LocalTime.of(18, 0));
        });
        assertThat(slots).filteredOn(s -> s.getDayOfWeek() == 1)
                .extracting(HousingTimeslot::getStartTime)
                .containsOnly(LocalTime.of(9, 0), LocalTime.of(14, 0));
    }

    @Test
    @DisplayName("createTimeslots=false：不建任何时段")
    void batchGenerate_createTimeslotsFalse_skips() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setCreateTimeslots(false);

        BatchGenerateResultVO result = runAsAdminAndGet(dto);

        assertThat(result.getTimeslotsCreated()).isEqualTo(0);
        verify(timeslotMapper, never()).insert(any(HousingTimeslot.class));
    }

    @Test
    @DisplayName("CUSTOM：按自定义列表建时段，跨天多条各自落库")
    void batchGenerate_customTimeslots_created() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setTimeslotMode("CUSTOM");
        dto.setTimeslots(List.of(item(6, "10:00", "11:30"), item(7, "15:00", "17:00")));

        BatchGenerateResultVO result = runAsAdminAndGet(dto);

        assertThat(result.getTimeslotsCreated()).isEqualTo(4);
        ArgumentCaptor<HousingTimeslot> captor = ArgumentCaptor.forClass(HousingTimeslot.class);
        verify(timeslotMapper, times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(s -> {
            assertThat(s.getDayOfWeek()).isIn(6, 7);
            assertThat(s.getStartTime()).isIn(LocalTime.of(10, 0), LocalTime.of(15, 0));
        });
    }

    @Test
    @DisplayName("幂等：同房源同星期同起止已存在 → 跳过不重复插入")
    void batchGenerate_existingTimeslot_skipped() {
        /* 房源自带回填主键 9000+houseId；已存在 houseId=1000 的周一 09:00-12:00 */
        HousingTimeslot existing = new HousingTimeslot();
        existing.setId(1L);
        existing.setHousingId(9000L + 1000L);
        existing.setDayOfWeek(1);
        existing.setStartTime(LocalTime.of(9, 0));
        existing.setEndTime(LocalTime.of(12, 0));
        existing.setIsAvailable(1);
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(existing));

        BatchGenerateResultVO result = runAsAdminAndGet(baseDto());

        /* 2 套 × 10 = 20 条中已有 1 条 → 只插 19 条 */
        assertThat(result.getTimeslotsCreated()).isEqualTo(19);
        verify(timeslotMapper, times(19)).insert(any(HousingTimeslot.class));
    }

    @Test
    @DisplayName("跳过的房屋不建时段：已有在架房源 → created=0 且不建时段")
    void batchGenerate_skippedHouse_noTimeslots() {
        Housing listed = new Housing();
        listed.setHouseId(1000L);
        listed.setStatus("AVAILABLE");
        lenient().when(housingMapper.selectList(any())).thenReturn(List.of(listed));
        BatchGenerateHousingDTO dto = baseDto();
        dto.setHouseIds(List.of(1000L));

        BatchGenerateResultVO result = runAsAdminAndGet(dto);

        assertThat(result.getCreated()).isZero();
        assertThat(result.getSkipped()).isEqualTo(1);
        assertThat(result.getTimeslotsCreated()).isZero();
        verify(timeslotMapper, never()).insert(any(HousingTimeslot.class));
    }

    @Test
    @DisplayName("CUSTOM 空列表：400 参数错误，不落房源")
    void batchGenerate_customEmpty_rejected() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setTimeslotMode("CUSTOM");

        assertThatThrownBy(() -> runAsAdmin(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("自定义时段不能为空");
        verify(housingMapper, never()).insert(any(Housing.class));
    }

    @Test
    @DisplayName("CUSTOM 起止越界（start>=end）：400 参数错误")
    void batchGenerate_customStartNotBeforeEnd_rejected() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setTimeslotMode("CUSTOM");
        dto.setTimeslots(List.of(item(1, "18:00", "09:00")));

        assertThatThrownBy(() -> runAsAdmin(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开始时间必须早于结束时间");
    }

    @Test
    @DisplayName("CUSTOM 星期越界（dayOfWeek=8）：400 参数错误")
    void batchGenerate_customIllegalDayOfWeek_rejected() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setTimeslotMode("CUSTOM");
        dto.setTimeslots(List.of(item(8, "09:00", "10:00")));

        assertThatThrownBy(() -> runAsAdmin(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("星期几取值 1~7");
    }

    @Test
    @DisplayName("时段来源非法（SUNDAY）：400 参数错误")
    void batchGenerate_illegalTimeslotMode_rejected() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setTimeslotMode("WEEKLY");

        assertThatThrownBy(() -> runAsAdmin(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时段来源仅支持 DEFAULT/CUSTOM");
    }

    @Test
    @DisplayName("createTimeslots=false 时模式非法仍拒绝（参数自洽性先于建时段开关）")
    void batchGenerate_falseButIllegalMode_rejected() {
        BatchGenerateHousingDTO dto = baseDto();
        dto.setCreateTimeslots(false);
        dto.setTimeslotMode("WEEKLY");

        assertThatThrownBy(() -> runAsAdmin(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时段来源仅支持 DEFAULT/CUSTOM");
    }

    private BatchGenerateHousingDTO.TimeslotItem item(int dayOfWeek, String start, String end) {
        BatchGenerateHousingDTO.TimeslotItem item = new BatchGenerateHousingDTO.TimeslotItem();
        item.setDayOfWeek(dayOfWeek);
        item.setStartTime(LocalTime.parse(start));
        item.setEndTime(LocalTime.parse(end));
        return item;
    }
}
