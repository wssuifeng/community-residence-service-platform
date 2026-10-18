package com.community.residence.common.evaluation.service;

import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.common.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.common.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.service.WorkOrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DEF-175：复用工单访问权限，子资源查询不得先于父资源授权。 */
@ExtendWith(MockitoExtension.class)
class EvaluationReadAccessTest {
    @Mock WorkOrderEvaluationMapper evaluationMapper;
    @Mock UnsatisfiedFollowupMapper followupMapper;
    @Mock WorkOrderMapper workOrderMapper;
    @Mock ResidentMapper residentMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock WorkOrderService workOrderService;
    @InjectMocks EvaluationService service;

    @Test
    void deniedOrderStopsBeforeEvaluationQuery() {
        when(workOrderService.getById(10L)).thenThrow(new ForbiddenException("工单不可读"));
        assertThatThrownBy(() -> service.getByWorkOrder(10L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(evaluationMapper, residentMapper);
    }

    @Test
    void absentOrderStopsBeforeEvaluationQuery() {
        when(workOrderService.getById(10L)).thenThrow(new ResourceNotFoundException("工单不存在"));
        assertThatThrownBy(() -> service.getByWorkOrder(10L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(evaluationMapper);
    }

    @Test
    void readableOrderReturnsEvaluationAfterParentGuard() {
        WorkOrderEvaluation evaluation = new WorkOrderEvaluation();
        evaluation.setId(50L); evaluation.setWorkOrderId(10L); evaluation.setResidentId(20L);
        when(evaluationMapper.selectOne(any())).thenReturn(evaluation);
        assertThat(service.getByWorkOrder(10L).getId()).isEqualTo(50L);
        var sequence = inOrder(workOrderService, evaluationMapper);
        sequence.verify(workOrderService).getById(10L);
        sequence.verify(evaluationMapper).selectOne(any());
    }

    @Test
    void readableOrderWithoutEvaluationKeeps404() {
        assertThatThrownBy(() -> service.getByWorkOrder(10L)).isInstanceOf(ResourceNotFoundException.class);
        var sequence = inOrder(workOrderService, evaluationMapper);
        sequence.verify(workOrderService).getById(10L);
        sequence.verify(evaluationMapper).selectOne(any());
    }
}
