package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.service.SysConfigService;
import com.community.residence.resident.vo.ResidentVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-010 修复回归（R45 两级数据授权）：居民列表/详情按管理员绑定社区过滤
 * （接口设计 9.2.1.7/9.2.1.8：ADMIN 限绑定社区内居民，经 residence_relation 关联）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("居民账号数据社区过滤修复回归（DEF-010）")
class ResidentScopeFixTest {

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration();
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Resident.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, ResidenceRelation.class);
    }

    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private SysOperationLogMapper sysOperationLogMapper;

    @InjectMocks
    private ResidentService residentService;

    private Resident resident;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        resident = new Resident();
        resident.setId(2L);
        resident.setUsername("resident2");
        resident.setRealName("李四");
        resident.setPhone("13900000002");
        resident.setStatus("ACTIVE");
    }

    /* ---- 详情：越绑定社区 404 ---- */

    @Test
    @DisplayName("ADMIN 查详情：居民无绑定社区内居住关系 → 404（原 200 全量资料）")
    void getById_crossCommunity_throws() {
        lenient().when(residentMapper.selectById(2L)).thenReturn(resident);
        /* 绑定社区 1 内的居住关系仅居民 3（居民 2 只在社区 2 在住）；
           selectList(any()) 不解析 wrapper，stub 语义=查询结果的命中行 */
        lenient().when(residenceRelationMapper.selectList(any()))
                .thenReturn(List.of(relation(3L, 1L)));
        try (MockedStatic<SecurityUtils> mocked = mockAdmin(Set.of(1L))) {
            assertThatThrownBy(() -> residentService.getById(2L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("ADMIN 查详情：居民在绑定社区内有居住关系 → 200")
    void getById_boundCommunity_ok() {
        lenient().when(residentMapper.selectById(2L)).thenReturn(resident);
        lenient().when(residenceRelationMapper.selectList(any()))
                .thenReturn(List.of(relation(2L, 1L)));
        try (MockedStatic<SecurityUtils> mocked = mockAdmin(Set.of(1L))) {
            assertThat(residentService.getById(2L).getRealName()).isEqualTo("李四");
        }
    }

    @Test
    @DisplayName("SUPER_ADMIN 查详情：不受社区过滤 → 200")
    void getById_superAdmin_ok() {
        lenient().when(residentMapper.selectById(2L)).thenReturn(resident);
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(1L, "superadmin", "SUPER_ADMIN", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);

            assertThat(residentService.getById(2L).getRealName()).isEqualTo("李四");
        }
    }

    /* ---- 列表：注入绑定社区过滤 ---- */

    @Test
    @DisplayName("ADMIN 列表：查询注入绑定社区内居民 ID 过滤（wrapper 含 IN 子句）")
    void page_admin_injectsCommunityFilter() {
        when(residenceRelationMapper.selectList(any()))
                .thenReturn(List.of(relation(2L, 1L), relation(5L, 1L)));
        Page<Resident> pageResult = new Page<>(1, 10);
        pageResult.setRecords(List.of(resident));
        pageResult.setTotal(1);
        when(residentMapper.selectPage(any(), any(LambdaQueryWrapper.class))).thenReturn(pageResult);

        try (MockedStatic<SecurityUtils> mocked = mockAdmin(Set.of(1L))) {
            PageVO<ResidentVO> vo = residentService.page(1, 10, null, null);
            assertThat(vo.getRecords()).hasSize(1);

            ArgumentCaptor<LambdaQueryWrapper<Resident>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(residentMapper).selectPage(any(), captor.capture());
            assertThat(captor.getValue().getSqlSegment()).contains("IN");
        }
    }

    @Test
    @DisplayName("ADMIN 未绑定任何社区：列表直接空页，不触库查询")
    void page_adminNoBinding_empty() {
        try (MockedStatic<SecurityUtils> mocked = mockAdmin(Set.of())) {
            PageVO<ResidentVO> vo = residentService.page(1, 10, null, null);
            assertThat(vo.getRecords()).isEmpty();
            verify(residentMapper, org.mockito.Mockito.never()).selectPage(any(), any());
        }
    }

    @Test
    @DisplayName("SUPER_ADMIN 列表：不注入过滤（全量口径）")
    void page_superAdmin_noFilter() {
        Page<Resident> pageResult = new Page<>(1, 10);
        pageResult.setRecords(List.of(resident));
        pageResult.setTotal(1);
        when(residentMapper.selectPage(any(), any(LambdaQueryWrapper.class))).thenReturn(pageResult);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(1L, "superadmin", "SUPER_ADMIN", Set.of()));
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(false);

            assertThat(residentService.page(1, 10, null, null).getRecords()).hasSize(1);
            verify(residenceRelationMapper, org.mockito.Mockito.never()).selectList(any());
        }
    }

    /* ---- 通用脚手架 ---- */

    private MockedStatic<SecurityUtils> mockAdmin(Set<Long> communityIds) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUser)
                .thenReturn(new UserContext(9L, "admin1", "ADMIN", communityIds));
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.ADMIN)).thenReturn(true);
        mocked.when(SecurityUtils::getCommunityIds).thenReturn(communityIds);
        return mocked;
    }

    private ResidenceRelation relation(Long residentId, Long communityId) {
        ResidenceRelation r = new ResidenceRelation();
        r.setResidentId(residentId);
        r.setCommunityId(communityId);
        return r;
    }
}
