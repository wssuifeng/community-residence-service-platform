package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.HouseManageItemVO;
import com.community.residence.interceptor.DataScopeInterceptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 房屋管理分页列表（接口设计.md 9.15.3）回归：筛选条件装配（社区/楼栋/单元/
 * 房号关键字 + 挂牌三态）、批量装配（名称与挂牌摘要一次取回）、
 * coverImage 取首图、未挂牌行为 null、ADMIN 数据范围的拦截器注入。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("房屋管理分页列表（9.15.3）")
class HouseManageListTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private CommunityService communityService;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private HousingService housingService;

    private House house1;
    private House house2;

    /** LambdaQueryWrapper 生成列名依赖实体元数据缓存，纯单元测试需手工初始化（同公告数据权限回归先例） */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, House.class);
        TableInfoHelper.initTableInfo(assistant, Unit.class);
        TableInfoHelper.initTableInfo(assistant, Building.class);
        TableInfoHelper.initTableInfo(assistant, Community.class);
        TableInfoHelper.initTableInfo(assistant, Housing.class);
    }

    @BeforeEach
    void setUp() {
        Community community = new Community();
        community.setId(1L);
        community.setName("清源里");

        Building building = new Building();
        building.setId(100L);
        building.setCommunityId(1L);
        building.setName("1 号楼");

        Unit unit = new Unit();
        unit.setId(200L);
        unit.setCommunityId(1L);
        unit.setBuildingId(100L);
        unit.setName("1 单元");

        house1 = new House();
        house1.setId(1000L);
        house1.setCommunityId(1L);
        house1.setUnitId(200L);
        house1.setHouseNumber("101");
        house1.setFloor(3);
        house1.setArea(new BigDecimal("88.50"));
        house1.setLayout("2室1厅1卫");
        house1.setStatus("VACANT");

        house2 = new House();
        house2.setId(1001L);
        house2.setCommunityId(1L);
        house2.setUnitId(200L);
        house2.setHouseNumber("102");
        house2.setStatus("OCCUPIED");

        lenient().when(unitMapper.selectList(any())).thenReturn(List.of(unit));
        lenient().when(buildingMapper.selectList(any())).thenReturn(List.of(building));
        lenient().when(communityMapper.selectList(any())).thenReturn(List.of(community));
        lenient().when(housingMapper.selectList(any())).thenReturn(List.of());
        lenient().when(houseMapper.selectPage(any(), any()))
                .thenReturn(pageOf(house1, house2, 2L));
    }

    private Page<House> pageOf(House house, House other, long total) {
        Page<House> result = new Page<>(1, 20);
        result.setRecords(other == null ? List.of(house) : List.of(house, other));
        result.setTotal(total);
        return result;
    }

    private Housing housing(Long id, Long houseId, String images) {
        Housing entity = new Housing();
        entity.setId(id);
        entity.setHouseId(houseId);
        entity.setCommunityId(1L);
        entity.setTitle("1 号楼1 单元101·精装房源");
        entity.setStatus("AVAILABLE");
        entity.setMonthlyRent(new BigDecimal("2000"));
        entity.setDeposit(new BigDecimal("4000"));
        entity.setRentType("RENT");
        entity.setImages(images);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Wrapper<House> capturedWrapper() {
        ArgumentCaptor<Wrapper<House>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(houseMapper).selectPage(any(), captor.capture());
        return captor.getValue();
    }

    /* ---- 筛选条件装配 ---- */

    @Test
    @DisplayName("listing=all：不加挂牌子查询；社区/单元/房号关键字/排序条件齐备")
    void pageHouseManage_listingAll_conditions() {
        housingService.pageHouseManage(1L, null, 200L, "101", null, 1, 20);

        String segment = capturedWrapper().getSqlSegment();
        assertThat(segment).contains("community_id = ").contains("unit_id = ");
        assertThat(segment).contains("house_number LIKE ");
        assertThat(segment).doesNotContain("EXISTS");
        assertThat(segment).contains("ORDER BY community_id ASC,unit_id ASC,house_number ASC");
    }

    @Test
    @DisplayName("listing=listed：EXISTS 子查询（存在 housing 记录即已挂牌）")
    void pageHouseManage_listingListed_exists() {
        housingService.pageHouseManage(null, null, null, null, "listed", 1, 20);

        String segment = capturedWrapper().getSqlSegment();
        assertThat(segment).contains("EXISTS (SELECT 1 FROM housing h WHERE h.house_id = house.id)");
        assertThat(segment).doesNotContain("NOT EXISTS");
    }

    @Test
    @DisplayName("listing=unlisted：NOT EXISTS 子查询（无 housing 记录即未挂牌）")
    void pageHouseManage_listingUnlisted_notExists() {
        housingService.pageHouseManage(null, null, null, null, "unlisted", 1, 20);

        assertThat(capturedWrapper().getSqlSegment())
                .contains("NOT EXISTS (SELECT 1 FROM housing h WHERE h.house_id = house.id)");
    }

    @Test
    @DisplayName("listing 非法值：拒绝（INVALID_PARAM），不发起分页查询")
    void pageHouseManage_listingInvalid_rejected() {
        assertThatThrownBy(() -> housingService.pageHouseManage(null, null, null, null, "SOLD", 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("all/listed/unlisted");
        verify(houseMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("楼栋筛选：先解析楼栋下单元集合再按 unit_id IN 收窄")
    void pageHouseManage_filterByBuilding() {
        housingService.pageHouseManage(1L, 100L, null, null, "all", 1, 20);

        assertThat(capturedWrapper().getSqlSegment()).contains("unit_id IN (");
        ArgumentCaptor<Wrapper<Unit>> unitWrapper = ArgumentCaptor.forClass(Wrapper.class);
        /* 两次：楼栋下单元解析（首查）+ 装配名称（批量查）——均单条查询 */
        verify(unitMapper, times(2)).selectList(unitWrapper.capture());
        assertThat(unitWrapper.getAllValues().get(0).getSqlSegment()).contains("building_id = ");
    }

    @Test
    @DisplayName("楼栋下无单元：直接返回空页，不发起房屋分页查询")
    void pageHouseManage_buildingWithoutUnit_emptyPage() {
        when(unitMapper.selectList(any())).thenReturn(List.of());

        PageVO<HouseManageItemVO> result =
                housingService.pageHouseManage(1L, 100L, null, null, "all", 1, 20);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verify(houseMapper, never()).selectPage(any(), any());
    }

    /* ---- 装配 ---- */

    @Test
    @DisplayName("装配：社区/楼栋/单元名称与挂牌摘要一次批量取回（各一条查询，无 N+1）")
    void pageHouseManage_assembleNamesAndHousing() {
        when(housingMapper.selectList(any())).thenReturn(List.of(
                housing(500L, 1000L, "/uploads/a.png,/uploads/b.png")));

        PageVO<HouseManageItemVO> result =
                housingService.pageHouseManage(1L, null, null, null, "all", 1, 20);

        assertThat(result.getTotal()).isEqualTo(2);
        HouseManageItemVO listed = result.getRecords().get(0);
        assertThat(listed.getHouseId()).isEqualTo(1000L);
        assertThat(listed.getCommunityName()).isEqualTo("清源里");
        assertThat(listed.getBuildingId()).isEqualTo(100L);
        assertThat(listed.getBuildingName()).isEqualTo("1 号楼");
        assertThat(listed.getUnitName()).isEqualTo("1 单元");
        assertThat(listed.getHouseNumber()).isEqualTo("101");
        assertThat(listed.getFloor()).isEqualTo(3);
        assertThat(listed.getArea()).isEqualByComparingTo("88.50");
        assertThat(listed.getLayout()).isEqualTo("2室1厅1卫");
        assertThat(listed.getHouseStatus()).isEqualTo("VACANT");
        assertThat(listed.getHousing()).isNotNull();
        assertThat(listed.getHousing().getId()).isEqualTo(500L);
        assertThat(listed.getHousing().getMonthlyRent()).isEqualByComparingTo("2000");

        /* 未挂牌房屋 housing 为 null */
        assertThat(result.getRecords().get(1).getHousing()).isNull();

        /* 装配批量查询各一次：单元/楼栋/社区/房源（逐行查询即 N+1） */
        verify(unitMapper, times(1)).selectList(any());
        verify(buildingMapper, times(1)).selectList(any());
        verify(communityMapper, times(1)).selectList(any());
        verify(housingMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("coverImage：取 images 逗号串首个非空项（原样返回 /uploads 相对路径）")
    void pageHouseManage_coverImageFirstNonBlank() {
        when(housingMapper.selectList(any())).thenReturn(List.of(
                housing(500L, 1000L, " , /uploads/cover-101.png ,/uploads/b.png")));

        HouseManageItemVO item = housingService
                .pageHouseManage(1L, null, null, null, "all", 1, 20).getRecords().get(0);

        assertThat(item.getHousing().getCoverImage()).isEqualTo("/uploads/cover-101.png");
    }

    @Test
    @DisplayName("coverImage：images 为空串/全空项 → null")
    void pageHouseManage_coverImageAbsent() {
        when(housingMapper.selectList(any())).thenReturn(List.of(
                housing(500L, 1000L, " , ")));

        HouseManageItemVO item = housingService
                .pageHouseManage(1L, null, null, null, "all", 1, 20).getRecords().get(0);

        assertThat(item.getHousing().getCoverImage()).isNull();
    }

    @Test
    @DisplayName("同一房屋多条挂牌：取 id 最大者")
    void pageHouseManage_latestHousingWins() {
        when(housingMapper.selectList(any())).thenReturn(List.of(
                housing(500L, 1000L, "/uploads/old.png"),
                housing(900L, 1000L, "/uploads/new.png")));

        HouseManageItemVO item = housingService
                .pageHouseManage(1L, null, null, null, "all", 1, 20).getRecords().get(0);

        assertThat(item.getHousing().getId()).isEqualTo(900L);
        assertThat(item.getHousing().getCoverImage()).isEqualTo("/uploads/new.png");
    }

    /* ---- 数据级权限：ADMIN 绑定社区由拦截器注入 ---- */

    @Test
    @DisplayName("ADMIN 数据范围：服务生成的 SQL 经 DataScopeInterceptor 注入绑定社区过滤")
    void pageHouseManage_adminScopeInjectedByInterceptor() throws Exception {
        housingService.pageHouseManage(null, null, null, null, "unlisted", 1, 20);
        String sql = "SELECT id, unit_id, community_id, house_number, status FROM house WHERE (is_deleted = 0) AND "
                + capturedWrapper().getSqlSegment().replaceAll("#\\{.+?\\}", "?");
        BoundSql boundSql = new BoundSql(new Configuration(), sql, List.of(), null);
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getId()).thenReturn("com.community.residence.community.mapper.HouseMapper.selectPage");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            new DataScopeInterceptor().beforeQuery(null, ms, null, null, null, boundSql);
        }

        /* house 表带 community_id 且不在跳过名单：where 被追加绑定社区过滤 */
        assertThat(boundSql.getSql()).contains("community_id IN (1)");
        assertThat(boundSql.getSql()).contains("NOT EXISTS");
    }

    @Test
    @DisplayName("SUPER_ADMIN：拦截器不改写 SQL（全社区可见）")
    void pageHouseManage_superAdminScopeNotInjected() throws Exception {
        String sql = "SELECT id, community_id FROM house WHERE (is_deleted = 0)";
        BoundSql boundSql = new BoundSql(new Configuration(), sql, List.of(), null);
        MappedStatement ms = mock(MappedStatement.class);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(1L, "superadmin", "SUPER_ADMIN", Set.of()));
            new DataScopeInterceptor().beforeQuery(null, ms, null, null, null, boundSql);
        }

        assertThat(boundSql.getSql()).isEqualTo(sql);
    }
}
