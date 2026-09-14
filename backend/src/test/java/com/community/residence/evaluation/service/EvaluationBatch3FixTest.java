package com.community.residence.evaluation.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第三批修复回归：DEF-028（hasFollowup 过滤）+ 第四批 DEF-035（SQL 级过滤 + total 全集） */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价第三/四批修复回归（DEF-028/035）")
class EvaluationBatch3FixTest {

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(configuration, "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, WorkOrderEvaluation.class);
    }

    @Mock
    private WorkOrderEvaluationMapper evaluationMapper;
    @Mock
    private UnsatisfiedFollowupMapper followupMapper;
    @Mock
    private WorkOrderMapper workOrderMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private EvaluationService evaluationService;

    @Test
    @DisplayName("DEF-035：hasFollowup=true 注入 EXISTS 子查询条件（SQL 级过滤，total 为过滤后全集）")
    void unsatisfied_hasFollowupTrue_sqlExistsFilter() {
        mockUnsatisfiedPage(evaluation(1L));
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, true);

        assertThat(vo.getRecords()).hasSize(1);
        assertThat(vo.getTotal()).isEqualTo(1);
        /* wrapper 断言：EXISTS 子查询进入 SQL 片段（不再是页内内存过滤） */
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkOrderEvaluation>>
                wrapperCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(evaluationMapper).selectPage(any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sql).contains("EXISTS");
        assertThat(sql).contains("unsatisfied_followup");
    }

    @Test
    @DisplayName("DEF-035：hasFollowup=false 注入 NOT EXISTS 子查询条件")
    void unsatisfied_hasFollowupFalse_sqlNotExistsFilter() {
        mockUnsatisfiedPage(evaluation(2L));
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, false);

        assertThat(vo.getRecords()).hasSize(1);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkOrderEvaluation>>
                wrapperCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(evaluationMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("NOT EXISTS");
    }

    @Test
    @DisplayName("DEF-035：hasFollowup 缺省无子查询条件（全量，向后兼容）")
    void unsatisfied_noFilter_returnsAll() {
        mockUnsatisfiedPage(evaluation(1L), evaluation(2L));
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, null);

        assertThat(vo.getRecords()).hasSize(2);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkOrderEvaluation>>
                wrapperCaptor = org.mockito.ArgumentCaptor.forClass(
                com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(evaluationMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).doesNotContain("EXISTS");
    }

    /* ---- 脚手架 ---- */

    private void mockUnsatisfiedPage(WorkOrderEvaluation... evaluations) {
        when(evaluationMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<WorkOrderEvaluation> p = inv.getArgument(0);
            p.setRecords(List.of(evaluations));
            p.setTotal(evaluations.length);
            return p;
        });
    }

    private WorkOrderEvaluation evaluation(Long id) {
        WorkOrderEvaluation e = new WorkOrderEvaluation();
        e.setId(id);
        e.setWorkOrderId(100L + id);
        e.setResidentId(1L);
        e.setCommunityId(1L);
        e.setRating(2);
        e.setIsSatisfied(0);
        return e;
    }
}
