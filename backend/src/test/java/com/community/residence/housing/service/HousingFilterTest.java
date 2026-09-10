package com.community.residence.housing.service;

import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.housing.dto.CreateHousingDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.vo.HousingVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 房源户型/租售类型筛选测试（R53 v1.2）：两维独立与组合筛选、无效值 400、创建落值 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HousingService 筛选维度单元测试")
class HousingFilterTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private com.community.residence.housing.mapper.HousingTimeslotMapper timeslotMapper;
    @Mock
    private com.community.residence.housing.mapper.ViewingAppointmentMapper appointmentMapper;
    @Mock
    private CommunityService communityService;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private HousingService housingService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Housing.class);
        TableInfoHelper.initTableInfo(assistant,
                com.community.residence.housing.entity.ViewingAppointment.class);
    }

    private void mockEmptyPage() {
        when(housingMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<Housing> p = inv.getArgument(0);
                    p.setRecords(java.util.List.of());
                    p.setTotal(0);
                    return p;
                });
    }

    @Test
    @DisplayName("layout 独立筛选：wrapper 含 layout = ?")
    void page_layoutFilter_applied() {
        mockEmptyPage();
        housingService.page(1, 10, null, null, null, null, null, "2室1厅", null);
        ArgumentCaptor<LambdaQueryWrapper<Housing>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(housingMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("layout =");
    }

    @Test
    @DisplayName("rentType 独立筛选：wrapper 含 rent_type = ?")
    void page_rentTypeFilter_applied() {
        mockEmptyPage();
        housingService.page(1, 10, null, null, null, null, null, null, "RENT");
        ArgumentCaptor<LambdaQueryWrapper<Housing>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(housingMapper).selectPage(any(), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("rent_type =");
    }

    @Test
    @DisplayName("两维组合筛选：layout + rentType 同时生效")
    void page_combinedFilters_applied() {
        mockEmptyPage();
        housingService.page(1, 10, null, null, null, null, null, "2室1厅", "SALE");
        ArgumentCaptor<LambdaQueryWrapper<Housing>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(housingMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertThat(sql).contains("layout =");
        assertThat(sql).contains("rent_type =");
    }

    @Test
    @DisplayName("无效 rentType：400 拒绝")
    void page_invalidRentType_rejected() {
        assertThatThrownBy(() -> housingService.page(1, 10, null, null, null, null, null, null, "LEASE"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("租售类型仅支持 RENT/SALE");
    }

    @Test
    @DisplayName("创建落值：layout 冗余自 house.layout、rentType 默认 RENT")
    void create_setsLayoutAndDefaultRentType() {
        House house = new House();
        house.setId(5L);
        house.setCommunityId(3L);
        house.setLayout("3室2厅");
        when(houseMapper.selectById(5L)).thenReturn(house);
        when(housingMapper.selectCount(any())).thenReturn(0L);
        when(housingMapper.insert(any(Housing.class))).thenAnswer(inv -> {
            inv.getArgument(0, Housing.class).setId(30L);
            return 1;
        });
        com.community.residence.community.entity.Community community =
                new com.community.residence.community.entity.Community();
        community.setId(3L);
        community.setName("C3");
        org.mockito.Mockito.lenient().when(communityService.requireCommunity(3L)).thenReturn(community);

        CreateHousingDTO dto = new CreateHousingDTO();
        dto.setHouseId(5L);
        dto.setTitle("测试房源");
        dto.setMonthlyRent(new BigDecimal("2000"));
        /* rentType 不传 → 默认 RENT */

        HousingVO vo = housingService.create(dto);

        ArgumentCaptor<Housing> captor = ArgumentCaptor.forClass(Housing.class);
        verify(housingMapper).insert(captor.capture());
        assertThat(captor.getValue().getLayout()).isEqualTo("3室2厅");
        assertThat(captor.getValue().getRentType()).isEqualTo("RENT");
        assertThat(vo.getLayout()).isEqualTo("3室2厅");
    }
}
