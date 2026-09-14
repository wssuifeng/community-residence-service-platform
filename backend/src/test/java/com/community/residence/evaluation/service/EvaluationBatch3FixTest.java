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
import static org.mockito.Mockito.when;

/** 第三批修复回归：DEF-028（不满意评价列表 hasFollowup 服务端过滤） */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价第三批修复回归（DEF-028）")
class EvaluationBatch3FixTest {

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
    @DisplayName("DEF-028：hasFollowup=true 仅返回已跟进评价（服务端过滤消前端 N+1）")
    void unsatisfied_hasFollowupTrue_filtersFollowed() {
        mockUnsatisfiedPage(evaluation(1L), evaluation(2L));
        /* 评价 1 已跟进、评价 2 未跟进 */
        when(followupMapper.selectCount(any())).thenReturn(1L, 0L);
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, true);

        assertThat(vo.getRecords()).hasSize(1);
        assertThat(vo.getRecords().get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("DEF-028：hasFollowup=false 仅返回待跟进评价")
    void unsatisfied_hasFollowupFalse_filtersPending() {
        mockUnsatisfiedPage(evaluation(1L), evaluation(2L));
        when(followupMapper.selectCount(any())).thenReturn(1L, 0L);
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, false);

        assertThat(vo.getRecords()).hasSize(1);
        assertThat(vo.getRecords().get(0).getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("DEF-028：hasFollowup 缺省返回全量（向后兼容）")
    void unsatisfied_noFilter_returnsAll() {
        mockUnsatisfiedPage(evaluation(1L), evaluation(2L));
        lenient().when(workOrderMapper.selectById(any())).thenReturn(null);
        lenient().when(residentMapper.selectById(any())).thenReturn(null);

        var vo = evaluationService.unsatisfied(1, 10, null);

        assertThat(vo.getRecords()).hasSize(2);
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
