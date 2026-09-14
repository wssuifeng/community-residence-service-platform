package com.community.residence.workorder.service;

import com.community.residence.community.service.CommunityService;
import com.community.residence.workorder.entity.ServiceCategory;
import com.community.residence.workorder.mapper.ServiceCategoryMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import com.community.residence.workorder.vo.CategoryVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第四批修复回归：DEF-041（服务类别树停用过滤 + applyDto 补 isActive） */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务类别树停用过滤回归（DEF-041）")
class ServiceCategoryActiveFilterTest {

    @Mock
    private ServiceCategoryMapper categoryMapper;
    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private CommunityService communityService;

    @InjectMocks
    private ServiceCategoryService categoryService;

    @Test
    @DisplayName("DEF-041 正例：居民树查询注入 is_active=1 条件（停用类别不再出现在提交选项）")
    void treeByCommunity_filtersInactive() {
        /* mock 模拟 SQL 过滤语义：is_active=1 条件下的返回集（停用父类 2 及其子类 12 不返回） */
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, null, 1), category(11L, 1L, 1)));

        List<CategoryVO> tree = categoryService.treeByCommunity(1L);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getId()).isEqualTo(1L);
        assertThat(tree.get(0).getChildren()).hasSize(1);
        assertThat(tree.get(0).getChildren().get(0).getId()).isEqualTo(11L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceCategory>>
                captor = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(categoryMapper).selectList(captor.capture());
        /* wrapper 断言：过滤条件真实注入（服务层确有 is_active 条件） */
        assertThat(captor.getValue().getSqlSegment()).contains("is_active");
    }

    @Test
    @DisplayName("DEF-041：管理端全量树不过滤（含停用，供配置管理）")
    void treeByCommunityAll_keepsInactive() {
        when(categoryMapper.selectList(any())).thenReturn(List.of(
                category(1L, null, 1), category(2L, null, 0)));

        List<CategoryVO> tree = categoryService.treeByCommunityAll(1L);

        assertThat(tree).hasSize(2);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ServiceCategory>>
                captor = ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(categoryMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("is_active");
    }

    private ServiceCategory category(Long id, Long parentId, int isActive) {
        ServiceCategory c = new ServiceCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setCommunityId(1L);
        c.setName("类别" + id);
        c.setIsActive(isActive);
        return c;
    }
}
