package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.vo.HousingVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 浏览计数实时化（DEF-063）：展示口径 = DB 值 + 未回写 Redis 增量。
 * recordView 返回计入本次后的最新数；详情单键 get、列表 multiGet 合并；
 * Redis 异常一律回落 DB 值不抛错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("浏览计数实时化（DB + 未回写增量）")
class HousingViewCountTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private CommunityService communityService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private HousingService housingService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Housing.class);
    }

    private Housing housing(Long id, int viewCount) {
        Housing housing = new Housing();
        housing.setId(id);
        housing.setCommunityId(7L);
        housing.setHouseId(70L + id);
        housing.setStatus("AVAILABLE");
        housing.setViewCount(viewCount);
        return housing;
    }

    private void stubCommunity() {
        Community community = new Community();
        community.setId(7L);
        community.setName("测试社区");
        lenient().when(communityService.requireCommunity(7L)).thenReturn(community);
    }

    /* ---- recordView：返回计入本次后的最新数 ---- */

    @Test
    @DisplayName("记录浏览：DB 5 + 本次 INCR 后增量 3 → 返回 8（不必等 5 分钟回写）")
    void recordView_returnsDbPlusPendingDelta() {
        stubCommunity();
        when(housingMapper.selectById(1L)).thenReturn(housing(1L, 5));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(HousingService.VIEW_COUNT_KEY_PREFIX + 1L)).thenReturn(3L);

        assertThat(housingService.recordView(1L)).isEqualTo(8L);
    }

    @Test
    @DisplayName("记录浏览：增量键首次创建（INCR 返回 1）→ 返回 DB+1")
    void recordView_firstIncrement_returnsDbPlusOne() {
        stubCommunity();
        when(housingMapper.selectById(2L)).thenReturn(housing(2L, 0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(HousingService.VIEW_COUNT_KEY_PREFIX + 2L)).thenReturn(1L);

        assertThat(housingService.recordView(2L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("记录浏览：Redis 不可用 → 直写 DB 并回读新值（不抛错）")
    void recordView_redisDown_writesDbAndReturnsNewValue() {
        stubCommunity();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        when(housingMapper.update(isNull(), any())).thenReturn(1);
        /* 首次读为直写前快照，第二次读为直写后回读的 DB 新值 */
        when(housingMapper.selectById(3L)).thenReturn(housing(3L, 9), housing(3L, 10));

        assertThat(housingService.recordView(3L)).isEqualTo(10L);
        verify(housingMapper).update(isNull(), any());
    }

    /* ---- 详情：单键 get 合并增量 ---- */

    @Test
    @DisplayName("房源详情：viewCount = DB 10 + 增量 4 = 14")
    void getById_mergesPendingDelta() {
        stubCommunity();
        when(housingMapper.selectById(1L)).thenReturn(housing(1L, 10));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(HousingService.VIEW_COUNT_KEY_PREFIX + 1L)).thenReturn("4");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            HousingVO vo = housingService.getById(1L);

            assertThat(vo.getViewCount()).isEqualTo(14);
        }
    }

    @Test
    @DisplayName("房源详情：Redis 抛异常 → 回落 DB 值 10，只读路径不抛错")
    void getById_redisFailure_fallsBackToDbValue() {
        stubCommunity();
        when(housingMapper.selectById(1L)).thenReturn(housing(1L, 10));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            HousingVO vo = housingService.getById(1L);

            assertThat(vo.getViewCount()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("房源详情：增量键值非法（非数字）→ 按 0 计，不抛错")
    void getById_illegalDeltaText_treatedAsZero() {
        stubCommunity();
        when(housingMapper.selectById(1L)).thenReturn(housing(1L, 10));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(HousingService.VIEW_COUNT_KEY_PREFIX + 1L)).thenReturn("abc");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            assertThat(housingService.getById(1L).getViewCount()).isEqualTo(10);
        }
    }

    /* ---- 列表：multiGet 批量合并增量 ---- */

    @Test
    @DisplayName("房源列表：按页内 id 集合 multiGet 合并（无增量的行保持 DB 值）")
    void page_mergesPendingDeltasByMultiGet() {
        stubCommunity();
        when(housingMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
            Page<Housing> p = inv.getArgument(0);
            p.setRecords(List.of(housing(1L, 10), housing(2L, 20)));
            p.setTotal(2);
            return p;
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        /* 第 1 行有 2 次未回写增量，第 2 行键不存在（multiGet 保留 null 占位） */
        when(valueOps.multiGet(List.of(HousingService.VIEW_COUNT_KEY_PREFIX + 1L,
                HousingService.VIEW_COUNT_KEY_PREFIX + 2L))).thenReturn(Arrays.asList("2", null));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            var result = housingService.page(1, 10, null, null, null, null, null, null, null);

            assertThat(result.getRecords()).extracting(HousingVO::getViewCount)
                    .containsExactly(12, 20);
        }
    }

    @Test
    @DisplayName("房源列表：multiGet 抛异常 → 整页回落 DB 值，不抛错")
    void page_multiGetFailure_fallsBackToDbValues() {
        stubCommunity();
        when(housingMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
            Page<Housing> p = inv.getArgument(0);
            p.setRecords(List.of(housing(1L, 10), housing(2L, 20)));
            p.setTotal(2);
            return p;
        });
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("redis down"));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            var result = housingService.page(1, 10, null, null, null, null, null, null, null);

            assertThat(result.getRecords()).extracting(HousingVO::getViewCount)
                    .containsExactly(10, 20);
        }
    }

    @Test
    @DisplayName("房源列表：空页不触碰 Redis（无逐行/无用查询）")
    void page_emptyRecords_noRedisAccess() {
        when(housingMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
            Page<Housing> p = inv.getArgument(0);
            p.setRecords(List.of());
            p.setTotal(0);
            return p;
        });

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);

            var result = housingService.page(1, 10, null, null, null, null, null, null, null);

            assertThat(result.getRecords()).isEmpty();
            verify(redisTemplate, org.mockito.Mockito.never()).opsForValue();
        }
    }
}
