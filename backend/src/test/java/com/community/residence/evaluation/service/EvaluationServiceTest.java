package com.community.residence.evaluation.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.evaluation.dto.CreateEvaluationDTO;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 评价业务逻辑测试：可评条件、一单一评、不满意跟进 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EvaluationService 单元测试")
class EvaluationServiceTest {

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

    @BeforeEach
    void setUp() {
        completedOrder = new WorkOrder();
        completedOrder.setId(1L);
        completedOrder.setResidentId(1L);
        completedOrder.setCommunityId(1L);
        completedOrder.setStatus(WorkOrderStatus.COMPLETED);
    }

    private CreateEvaluationDTO evalDto(boolean satisfied) {
        CreateEvaluationDTO dto = new CreateEvaluationDTO();
        dto.setRating(satisfied ? 5 : 2);
        dto.setIsSatisfied(satisfied);
        dto.setContent(satisfied ? "Great" : "Bad");
        return dto;
    }

    @Test
    @DisplayName("工单未完成：拒绝评价（EVALUATION_NOT_ALLOWED 5501）")
    void create_orderNotCompleted_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            completedOrder.setStatus(WorkOrderStatus.IN_PROGRESS);
            when(workOrderMapper.selectById(1L)).thenReturn(completedOrder);

            assertThatThrownBy(() -> evaluationService.create(1L, evalDto(true)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.EVALUATION_NOT_ALLOWED));
        }
    }

    @Test
    @DisplayName("重复评价：拒绝（EVALUATION_DUPLICATE 5502）")
    void create_duplicate_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            when(workOrderMapper.selectById(1L)).thenReturn(completedOrder);
            when(evaluationMapper.selectCount(any())).thenReturn(1L);

            assertThatThrownBy(() -> evaluationService.create(1L, evalDto(true)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.EVALUATION_DUPLICATE));
        }
    }

    @Test
    @DisplayName("非提交人评价：拒绝（403）")
    void create_notOwner_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(999L);
            when(workOrderMapper.selectById(1L)).thenReturn(completedOrder);

            assertThatThrownBy(() -> evaluationService.create(1L, evalDto(true)))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("不满意评价：跟进仅允许不满意评价，满意评价跟进拒绝")
    void followup_satisfiedEvaluation_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(() -> com.community.residence.common.context.SecurityUtils
                    .hasRole(anyString())).thenReturn(true);
            WorkOrderEvaluation satisfied = new WorkOrderEvaluation();
            satisfied.setId(1L);
            satisfied.setCommunityId(1L);
            satisfied.setIsSatisfied(1);
            when(evaluationMapper.selectById(1L)).thenReturn(satisfied);

            var dto = new com.community.residence.evaluation.dto.CreateFollowUpDTO();
            dto.setContent("follow up");
            assertThatThrownBy(() -> evaluationService.addFollowup(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仅不满意评价");
        }
    }

    @Test
    @DisplayName("统计：空数据返回零值不抛异常")
    void statistics_empty_returnsZeros() {
        when(evaluationMapper.selectCount(any())).thenReturn(0L);
        var result = evaluationService.statistics(null);
        assertThat(result.get("total")).isEqualTo(0L);
        assertThat(result.get("averageRating")).isEqualTo(0.0);
    }
}
