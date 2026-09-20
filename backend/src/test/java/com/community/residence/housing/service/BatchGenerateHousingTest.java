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
 * R62 批量挂牌细粒度回归：楼栋/单元/房屋三级收窄、标题后缀、
 * 跨社区 ID 拒绝、幂等跳过（既有在架房源）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("批量挂牌细粒度（R62）")
class BatchGenerateHousingTest {

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
    private HousingTimeslotMapper housingTimeslotMapper;

    @InjectMocks
    private HousingService housingService;

    private House house1;
    private House house2;
    private Unit unit1;
    private Unit unit2;
    private Building building1;

    @BeforeEach
    void setUp() {
        building1 = new Building();
        building1.setId(100L);
        building1.setCommunityId(1L);
        building1.setName("1 号楼");

        unit1 = new Unit();
        unit1.setId(200L);
        unit1.setCommunityId(1L);
        unit1.setBuildingId(100L);
        unit1.setName("1 单元");

        unit2 = new Unit();
        unit2.setId(201L);
        unit2.setCommunityId(1L);
        unit2.setBuildingId(100L);
        unit2.setName("2 单元");

        house1 = new House();
        house1.setId(1000L);
        house1.setCommunityId(1L);
        house1.setUnitId(200L);
        house1.setHouseNumber("101");
        house1.setLayout("三室一厅");

        house2 = new House();
        house2.setId(1001L);
        house2.setCommunityId(1L);
        house2.setUnitId(201L);
        house2.setHouseNumber("102");

        lenient().when(houseMapper.selectList(any())).thenReturn(List.of(house1, house2));
        lenient().when(unitMapper.selectList(any())).thenReturn(List.of(unit1, unit2));
        lenient().when(buildingMapper.selectList(any())).thenReturn(List.of(building1));
        /* 无在架房源（首查） */
        lenient().when(housingMapper.selectList(any())).thenReturn(List.of());
    }

    private BatchGenerateHousingDTO baseDto() {
        BatchGenerateHousingDTO dto = new BatchGenerateHousingDTO();
        dto.setCommunityId(1L);
        dto.setMonthlyRent(new BigDecimal("2000"));
        dto.setDeposit(new BigDecimal("4000"));
        dto.setRentType("RENT");
        return dto;
    }

    private void asAdmin() {
        org.mockito.Mockito.mockStatic(SecurityUtils.class);
    }

    @Test
    @DisplayName("整社区挂牌：全部无房源房屋生成 AVAILABLE")
    void batchGenerate_wholeCommunity() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));

            BatchGenerateResultVO result = housingService.batchGenerate(baseDto());

            assertThat(result.getCreated()).isEqualTo(2);
            verify(housingMapper, times(2)).insert(any(Housing.class));
        }
    }

    @Test
    @DisplayName("按单元收窄：仅目标单元下房屋挂牌")
    void batchGenerate_filterByUnit() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            BatchGenerateHousingDTO dto = baseDto();
            dto.setUnitIds(List.of(200L));

            BatchGenerateResultVO result = housingService.batchGenerate(dto);

            assertThat(result.getCreated()).isEqualTo(1);
            ArgumentCaptor<Housing> captor = ArgumentCaptor.forClass(Housing.class);
            verify(housingMapper).insert(captor.capture());
            assertThat(captor.getValue().getHouseId()).isEqualTo(1000L);
        }
    }

    @Test
    @DisplayName("按房屋收窄最细粒度：仅指定房屋挂牌")
    void batchGenerate_filterByHouse() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            BatchGenerateHousingDTO dto = baseDto();
            dto.setHouseIds(List.of(1001L));

            BatchGenerateResultVO result = housingService.batchGenerate(dto);

            assertThat(result.getCreated()).isEqualTo(1);
            ArgumentCaptor<Housing> captor = ArgumentCaptor.forClass(Housing.class);
            verify(housingMapper).insert(captor.capture());
            assertThat(captor.getValue().getHouseId()).isEqualTo(1001L);
        }
    }

    @Test
    @DisplayName("标题后缀：拼接「·精装房源·{后缀}」")
    void batchGenerate_titleSuffix() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            BatchGenerateHousingDTO dto = baseDto();
            dto.setHouseIds(List.of(1000L));
            dto.setTitleSuffix("特价");

            housingService.batchGenerate(dto);

            ArgumentCaptor<Housing> captor = ArgumentCaptor.forClass(Housing.class);
            verify(housingMapper).insert(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("1 号楼1 单元101·精装房源·特价");
        }
    }

    @Test
    @DisplayName("跨社区房屋 ID：拒绝（不落任何房源）")
    void batchGenerate_foreignHouseRejected() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            BatchGenerateHousingDTO dto = baseDto();
            dto.setHouseIds(List.of(1000L, 9999L));
            /* 归属校验查询只命中 1 条 → 与请求数不符 */
            lenient().when(houseMapper.selectList(any())).thenReturn(List.of(house1));

            assertThatThrownBy(() -> housingService.batchGenerate(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("不属于该社区");
            verify(housingMapper, never()).insert(any(Housing.class));
        }
    }

    @Test
    @DisplayName("幂等：已有在架房源的房屋跳过（created 只计新生成）")
    void batchGenerate_skipListed() {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            Housing listed = new Housing();
            listed.setHouseId(1000L);
            listed.setStatus("AVAILABLE");
            lenient().when(housingMapper.selectList(any())).thenReturn(List.of(listed));

            BatchGenerateResultVO result = housingService.batchGenerate(baseDto());

            assertThat(result.getCreated()).isEqualTo(1);
            assertThat(result.getSkipped()).isEqualTo(1);
        }
    }
}
