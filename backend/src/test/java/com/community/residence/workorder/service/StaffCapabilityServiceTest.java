package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.workorder.dto.SaveStaffCapabilityDTO;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.entity.StaffCommunity;
import com.community.residence.workorder.entity.StaffServiceCategory;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.StaffCommunityMapper;
import com.community.residence.workorder.mapper.StaffServiceCategoryMapper;
import com.community.residence.workorder.vo.StaffCandidateVO;
import com.community.residence.workorder.vo.StaffCapabilityVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 服务人员能力绑定测试：全量覆盖式保存（先删后插）、校验约束、ADMIN 可见范围收敛 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffCapabilityService 单元测试")
class StaffCapabilityServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private StaffCommunityMapper staffCommunityMapper;
    @Mock
    private StaffServiceCategoryMapper staffServiceCategoryMapper;
    @Mock
    private CommunityMapper communityMapper;
    @Mock
    private ServiceCategoryMapper categoryMapper;

    @InjectMocks
    private StaffCapabilityService staffCapabilityService;

    /** Lambda 列名与占位符求值依赖 TableInfo 缓存（纯单测无 Spring 上下文，需显式初始化） */
    @BeforeAll
    static void initLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), SysUser.class);
    }

    private SysUser staff(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("staff" + id);
        user.setRealName("张服务" + id);
        user.setPhone("13800001111");
        user.setRole(role);
        user.setStatus("ACTIVE");
        return user;
    }

    private Community community(Long id, String name) {
        Community community = new Community();
        community.setId(id);
        community.setName(name);
        return community;
    }

    private ServiceCategory category(Long id, String name) {
        ServiceCategory category = new ServiceCategory();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private StaffCommunity communityBinding(Long staffId, Long communityId) {
        StaffCommunity binding = new StaffCommunity();
        binding.setStaffId(staffId);
        binding.setCommunityId(communityId);
        return binding;
    }

    private StaffServiceCategory categoryBinding(Long staffId, Long categoryId) {
        StaffServiceCategory binding = new StaffServiceCategory();
        binding.setStaffId(staffId);
        binding.setCategoryId(categoryId);
        return binding;
    }

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockSecurity() {
        return mockStatic(com.community.residence.common.context.SecurityUtils.class);
    }

    @Test
    @DisplayName("保存绑定：全量覆盖（先删后插），返回最新常驻社区与擅长类别")
    void save_fullOverwrite_deletesThenInserts() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(7L)).thenReturn(staff(7L, RoleConstants.STAFF));
            when(communityMapper.selectById(1L)).thenReturn(community(1L, "幸福社区"));
            when(communityMapper.selectById(2L)).thenReturn(community(2L, "和平社区"));
            when(categoryMapper.selectById(5L)).thenReturn(category(5L, "水电维修"));
            when(staffCommunityMapper.selectList(any())).thenReturn(List.of(
                    communityBinding(7L, 1L), communityBinding(7L, 2L)));
            when(staffServiceCategoryMapper.selectList(any())).thenReturn(List.of(
                    categoryBinding(7L, 5L)));
            when(communityMapper.selectList(any())).thenReturn(List.of(
                    community(1L, "幸福社区"), community(2L, "和平社区")));
            when(categoryMapper.selectList(any())).thenReturn(List.of(category(5L, "水电维修")));

            SaveStaffCapabilityDTO dto = new SaveStaffCapabilityDTO();
            dto.setCommunityIds(List.of(1L, 2L));
            dto.setCategoryIds(List.of(5L));

            StaffCapabilityVO vo = staffCapabilityService.save(7L, dto);

            verify(staffCommunityMapper).delete(any());
            verify(staffServiceCategoryMapper).delete(any());
            verify(staffCommunityMapper, times(2)).insert(any(StaffCommunity.class));
            verify(staffServiceCategoryMapper).insert(any(StaffServiceCategory.class));
            assertThat(vo.getStaffId()).isEqualTo(7L);
            assertThat(vo.getCommunityIds()).containsExactly(1L, 2L);
            assertThat(vo.getCommunityNames()).containsExactly("幸福社区", "和平社区");
            assertThat(vo.getCategoryIds()).containsExactly(5L);
            assertThat(vo.getCategoryNames()).containsExactly("水电维修");
        }
    }

    @Test
    @DisplayName("保存绑定：列表为空/不传 → 清空对应维度绑定（只删不插）")
    void save_emptyLists_clearsBindings() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(7L)).thenReturn(staff(7L, RoleConstants.STAFF));
            SaveStaffCapabilityDTO dto = new SaveStaffCapabilityDTO();

            StaffCapabilityVO vo = staffCapabilityService.save(7L, dto);

            verify(staffCommunityMapper).delete(any());
            verify(staffServiceCategoryMapper).delete(any());
            verify(staffCommunityMapper, never()).insert(any(StaffCommunity.class));
            verify(staffServiceCategoryMapper, never()).insert(any(StaffServiceCategory.class));
            assertThat(vo.getCommunityIds()).isEmpty();
            assertThat(vo.getCategoryIds()).isEmpty();
        }
    }

    @Test
    @DisplayName("保存绑定：staffId 不存在 → 404")
    void save_missingStaff_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(7L)).thenReturn(null);

            assertThatThrownBy(() -> staffCapabilityService.save(7L, new SaveStaffCapabilityDTO()))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("服务人员不存在");
        }
    }

    @Test
    @DisplayName("保存绑定：staffId 非 STAFF 角色 → 404（不可绑定管理员）")
    void save_nonStaffUser_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(9L)).thenReturn(staff(9L, RoleConstants.ADMIN));

            assertThatThrownBy(() -> staffCapabilityService.save(9L, new SaveStaffCapabilityDTO()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(staffCommunityMapper, never()).delete(any());
        }
    }

    @Test
    @DisplayName("保存绑定：社区不存在（或不在管理员可访问范围）→ 5003，整批拒绝")
    void save_unknownCommunity_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(7L)).thenReturn(staff(7L, RoleConstants.STAFF));
            when(communityMapper.selectById(99L)).thenReturn(null);
            SaveStaffCapabilityDTO dto = new SaveStaffCapabilityDTO();
            dto.setCommunityIds(List.of(99L));

            assertThatThrownBy(() -> staffCapabilityService.save(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.DATA_NOT_FOUND))
                    .hasMessageContaining("社区不存在");
            verify(staffCommunityMapper, never()).delete(any());
        }
    }

    @Test
    @DisplayName("保存绑定：服务类别不存在 → 5003，整批拒绝")
    void save_unknownCategory_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(sysUserMapper.selectById(7L)).thenReturn(staff(7L, RoleConstants.STAFF));
            when(categoryMapper.selectById(99L)).thenReturn(null);
            SaveStaffCapabilityDTO dto = new SaveStaffCapabilityDTO();
            dto.setCategoryIds(List.of(99L));

            assertThatThrownBy(() -> staffCapabilityService.save(7L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("服务类别不存在");
            verify(staffServiceCategoryMapper, never()).delete(any());
        }
    }

    @Test
    @DisplayName("能力列表：ADMIN 未绑定社区 → 空页且不执行人员分页查询")
    void page_adminWithoutBoundCommunity_returnsEmpty() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getCommunityIds)
                    .thenReturn(Set.of());

            PageVO<StaffCapabilityVO> vo = staffCapabilityService.page(1, 20, null, null, null);

            assertThat(vo.getRecords()).isEmpty();
            verify(sysUserMapper, never()).selectPage(any(), any());
        }
    }

    @Test
    @DisplayName("能力列表：ADMIN 仅见绑定其绑定社区的人员（未绑定本社区者不出现）")
    void page_adminScopedToBoundCommunityStaff() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getCommunityIds)
                    .thenReturn(Set.of(1L));
            /* staff_community 中只有 2 号人员绑定了 1 号社区 */
            when(staffCommunityMapper.selectList(any())).thenReturn(List.of(communityBinding(2L, 1L)));
            when(sysUserMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<SysUser> p = inv.getArgument(0);
                p.setRecords(List.of(staff(2L, RoleConstants.STAFF)));
                p.setTotal(1);
                return p;
            });

            PageVO<StaffCapabilityVO> vo = staffCapabilityService.page(1, 20, null, null, "张");

            assertThat(vo.getTotal()).isEqualTo(1);
            assertThat(vo.getRecords().get(0).getStaffId()).isEqualTo(2L);
            /* 作用域 ID 已注入分页查询条件（IN 参数，求值后落参数映射） */
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>> captor =
                    ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(sysUserMapper).selectPage(any(), captor.capture());
            captor.getValue().getSqlSegment();
            assertThat(captor.getValue().getParamNameValuePairs())
                    .containsValue(2L).containsValue("STAFF");
        }
    }

    @Test
    @DisplayName("能力列表：超管不带筛选 → 全部 STAFF（未绑定任何社区者也返回）")
    void page_superAdmin_returnsAllStaff() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(false);
            when(sysUserMapper.selectPage(any(), any())).thenAnswer(inv -> {
                Page<SysUser> p = inv.getArgument(0);
                p.setRecords(List.of(staff(1L, RoleConstants.STAFF), staff(2L, RoleConstants.STAFF)));
                p.setTotal(2);
                return p;
            });

            PageVO<StaffCapabilityVO> vo = staffCapabilityService.page(1, 20, null, null, null);

            assertThat(vo.getRecords()).hasSize(2);
            assertThat(vo.getRecords().get(0).getCommunityIds()).isEmpty();
        }
    }

    @Test
    @DisplayName("能力详情：社区管理员查看绑定社区外人员 → 404")
    void getById_outsideAdminScope_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(RoleConstants.ADMIN)).thenReturn(true);
            mocked.when(com.community.residence.common.context.SecurityUtils::getCommunityIds)
                    .thenReturn(Set.of(1L));
            when(sysUserMapper.selectById(7L)).thenReturn(staff(7L, RoleConstants.STAFF));
            when(staffCommunityMapper.selectList(any())).thenReturn(List.of(communityBinding(2L, 1L)));

            assertThatThrownBy(() -> staffCapabilityService.getById(7L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("候选人员池：全量 STAFF（id/姓名/用户名），不做社区过滤")
    void candidates_returnsAllStaff() {
        when(sysUserMapper.selectList(any())).thenReturn(List.of(
                staff(1L, RoleConstants.STAFF), staff(2L, RoleConstants.STAFF)));

        List<StaffCandidateVO> candidates = staffCapabilityService.candidates();

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0).getId()).isEqualTo(1L);
        assertThat(candidates.get(0).getRealName()).isEqualTo("张服务1");
        assertThat(candidates.get(0).getUsername()).isEqualTo("staff1");
    }

    @Test
    @DisplayName("批量取绑定：常驻社区 ID 映射与名称拼接（无绑定人员不在结果中）")
    void communityNamesByStaff_groupsBindings() {
        when(staffCommunityMapper.selectList(any())).thenReturn(List.of(
                communityBinding(1L, 1L), communityBinding(1L, 2L)));
        when(communityMapper.selectList(any())).thenReturn(List.of(
                community(1L, "幸福社区"), community(2L, "和平社区")));

        Map<Long, List<Long>> ids = staffCapabilityService.communityIdsByStaff(List.of(1L, 2L));
        Map<Long, String> names = staffCapabilityService.communityNamesByStaff(List.of(1L, 2L));

        assertThat(ids).containsEntry(1L, List.of(1L, 2L)).doesNotContainKey(2L);
        assertThat(names).containsEntry(1L, "幸福社区，和平社区").doesNotContainKey(2L);
    }
}
