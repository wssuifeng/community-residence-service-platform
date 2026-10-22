package com.community.residence.common.evaluation.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.evaluation.entity.UnsatisfiedFollowup;
import com.community.residence.common.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.common.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.common.evaluation.mapper.WorkOrderEvaluationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 小区删除时的evaluation域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityEvaluationCleanupService {
    private final WorkOrderEvaluationMapper workOrderEvaluationMapper;
    private final UnsatisfiedFollowupMapper unsatisfiedFollowupMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeForOrders(List<Long> orderIds) {
            List<Long> evaluationIds = workOrderEvaluationMapper.selectList(
                    new LambdaQueryWrapper<WorkOrderEvaluation>()
                            .in(WorkOrderEvaluation::getWorkOrderId, orderIds))
                    .stream().map(WorkOrderEvaluation::getId).toList();
            if (!evaluationIds.isEmpty()) {
                unsatisfiedFollowupMapper.delete(new LambdaQueryWrapper<UnsatisfiedFollowup>()
                        .in(UnsatisfiedFollowup::getEvaluationId, evaluationIds));
                workOrderEvaluationMapper.deleteBatchIds(evaluationIds);
            }
    }
}
