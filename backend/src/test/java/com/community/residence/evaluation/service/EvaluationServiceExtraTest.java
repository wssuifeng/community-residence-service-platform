package com.community.residence.evaluation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.evaluation.dto.CreateEvaluationDTO;
import com.community.residence.evaluation.dto.CreateFollowUpDTO;
import com.community.residence.evaluation.entity.UnsatisfiedFollowup;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 评价服务补充测试：成功路径、跟进、统计、列表装配 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluationService 补充路径测试")
class EvaluationServiceExtraTest {

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

    private WorkOrder completedOrder;
    private WorkOrderEvaluation evaluation;

    @BeforeEach
    void setUp() {
        completedOrder = new WorkOrder();
        completedOrder.setId(1L);
        completedOrder.setOrderNo("WO1");
        completedOrder.setResidentId(1L);
        completedOrder.setCommunityId(1L);
        completedOrder.setStatus(WorkOrderStatus.COMPLETED);

        evaluation = new WorkOrderEvaluation();
        evaluation.setId(1L);
        evaluation.setWorkOrderId(1L);
        evaluation.setResidentId(1L);
        evaluation.setCommunityId(1L);
        evaluation.setRating(4);
        evaluation.setIsSatisfied(1);
    }

    private CreateEvaluationDTO evalDto(boolean satisfied) {
        CreateEvaluationDTO dto = new CreateEvaluationDTO();
        dto.setRating(satisfied ? 5 : 2);
        dto.setIsSatisfied(satisfied);
        dto.setContent(satisfied ? "Great" : "Bad");
        return dto;
    }

    @Test
    @DisplayName("评价成功：满意评价落库并装配工单号与居民姓名")
    void create_success_mapsVo() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(workOrderMapper.selectById(1L)).thenReturn(completedOrder);
            when(evaluationMapper.selectCount(any())).thenReturn(0L);
            when(evaluationMapper.insert(any(WorkOrderEvaluation.class))).thenReturn(1);
            Resident r = new Resident();
            r.setRealName("Zhang");
            when(residentMapper.selectById(1L)).thenReturn(r);

            var vo = evaluationService.create(1L, evalDto(true));
            assertThat(vo.getWorkOrderNo()).isEqualTo("WO1");
            assertThat(vo.getResidentName()).isEqualTo("Zhang");
            assertThat(vo.getIsSatisfied()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("按工单查评价：无评价 404")
    void getByWorkOrder_none_throws() {
        when(evaluationMapper.selectOne(any())).thenReturn(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> evaluationService.getByWorkOrder(1L))
                .isInstanceOf(com.community.residence.common.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("不满意跟进成功：留痕 + 装配跟进人")
    void followup_success() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
            evaluation.setIsSatisfied(0);
            when(evaluationMapper.selectById(1L)).thenReturn(evaluation);
            when(followupMapper.insert(any(UnsatisfiedFollowup.class))).thenReturn(1);
            com.community.residence.auth.entity.SysUser handler =
                    new com.community.residence.auth.entity.SysUser();
            handler.setRealName("Admin A");
            when(sysUserMapper.selectById(5L)).thenReturn(handler);

            CreateFollowUpDTO dto = new CreateFollowUpDTO();
            dto.setContent("contacted resident");
            var vo = evaluationService.addFollowup(1L, dto);

            assertThat(vo.getHandlerName()).isEqualTo("Admin A");
            assertThat(vo.getFollowupContent()).isEqualTo("contacted resident");
        }
    }

    @Test
    @DisplayName("统计：有数据时返回平均分与满意率")
    void statistics_withData() {
        when(evaluationMapper.selectCount(any())).thenReturn(2L);
        WorkOrderEvaluation e1 = new WorkOrderEvaluation();
        e1.setRating(5);
        e1.setIsSatisfied(1);
        WorkOrderEvaluation e2 = new WorkOrderEvaluation();
        e2.setRating(3);
        e2.setIsSatisfied(0);
        when(evaluationMapper.selectList(any())).thenReturn(List.of(e1, e2));

        var result = evaluationService.statistics(null);
        assertThat(result.get("total")).isEqualTo(2L);
        assertThat((Double) result.get("averageRating")).isEqualTo(4.0);
        assertThat((Double) result.get("satisfiedRate")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("不满意列表：分页装配（isSatisfied=0 过滤）")
    void unsatisfied_page() {
        when(evaluationMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<WorkOrderEvaluation> p = inv.getArgument(0);
                    evaluation.setIsSatisfied(0);
                    p.setRecords(List.of(evaluation));
                    p.setTotal(1);
                    return p;
                });
        when(workOrderMapper.selectById(1L)).thenReturn(completedOrder);
        when(residentMapper.selectById(1L)).thenReturn(new Resident());

        var vo = evaluationService.unsatisfied(1, 10);
        assertThat(vo.getTotal()).isEqualTo(1);
    }
}
