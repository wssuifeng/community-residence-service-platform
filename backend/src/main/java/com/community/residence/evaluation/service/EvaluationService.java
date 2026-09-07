package com.community.residence.evaluation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.WorkOrderStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.evaluation.dto.CreateEvaluationDTO;
import com.community.residence.evaluation.dto.CreateFollowUpDTO;
import com.community.residence.evaluation.entity.UnsatisfiedFollowup;
import com.community.residence.evaluation.entity.WorkOrderEvaluation;
import com.community.residence.evaluation.mapper.UnsatisfiedFollowupMapper;
import com.community.residence.evaluation.mapper.WorkOrderEvaluationMapper;
import com.community.residence.evaluation.vo.EvaluationVO;
import com.community.residence.evaluation.vo.FollowUpVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 评价业务逻辑：仅已完成工单可评价；一单一评；不满意评价支持跟进 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final WorkOrderEvaluationMapper evaluationMapper;
    private final UnsatisfiedFollowupMapper followupMapper;
    private final WorkOrderMapper workOrderMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;

    /* 提交评价：工单须 COMPLETED（居民确认后）；仅提交人；一单一评 */
    @Transactional(rollbackFor = Exception.class)
    public EvaluationVO create(Long workOrderId, CreateEvaluationDTO dto) {
        WorkOrder order = workOrderMapper.selectById(workOrderId);
        if (order == null) {
            throw new ResourceNotFoundException("工单不存在");
        }
        if (!order.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅工单提交人可评价");
        }
        if (!WorkOrderStatus.COMPLETED.equals(order.getStatus())
                && !WorkOrderStatus.CLOSED.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.EVALUATION_NOT_ALLOWED);
        }
        Long exists = evaluationMapper.selectCount(new LambdaQueryWrapper<WorkOrderEvaluation>()
                .eq(WorkOrderEvaluation::getWorkOrderId, workOrderId));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.EVALUATION_DUPLICATE);
        }

        WorkOrderEvaluation evaluation = new WorkOrderEvaluation();
        evaluation.setWorkOrderId(workOrderId);
        evaluation.setResidentId(order.getResidentId());
        evaluation.setCommunityId(order.getCommunityId());
        evaluation.setRating(dto.getRating());
        evaluation.setContent(dto.getContent());
        evaluation.setTags(dto.getTags());
        evaluation.setIsSatisfied(Boolean.TRUE.equals(dto.getIsSatisfied()) ? 1 : 0);
        evaluationMapper.insert(evaluation);
        return toVO(evaluation);
    }

    /** 按工单查评价（拥有工单访问权限的用户） */
    public EvaluationVO getByWorkOrder(Long workOrderId) {
        WorkOrderEvaluation evaluation = evaluationMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(WorkOrderEvaluation::getWorkOrderId, workOrderId));
        if (evaluation == null) {
            throw new ResourceNotFoundException("该工单暂无评价");
        }
        return toVO(evaluation);
    }

    public PageVO<EvaluationVO> page(long page, long size, Integer minRating, Integer maxRating) {
        LambdaQueryWrapper<WorkOrderEvaluation> wrapper = new LambdaQueryWrapper<WorkOrderEvaluation>()
                .ge(minRating != null, WorkOrderEvaluation::getRating, minRating)
                .le(maxRating != null, WorkOrderEvaluation::getRating, maxRating)
                .orderByDesc(WorkOrderEvaluation::getId);
        Page<WorkOrderEvaluation> result = evaluationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /** 不满意评价列表（ADMIN 跟进工作台） */
    public PageVO<EvaluationVO> unsatisfied(long page, long size) {
        Page<WorkOrderEvaluation> result = evaluationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<WorkOrderEvaluation>()
                        .eq(WorkOrderEvaluation::getIsSatisfied, 0)
                        .orderByDesc(WorkOrderEvaluation::getId));
        return PageVO.of(result.convert(this::toVO));
    }

    /* 不满意跟进：仅不满意评价可跟进；多次跟进全量留痕 */
    @Transactional(rollbackFor = Exception.class)
    public FollowUpVO addFollowup(Long evaluationId, CreateFollowUpDTO dto) {
        WorkOrderEvaluation evaluation = requireEvaluation(evaluationId);
        SecurityUtils.checkCommunityAccess(evaluation.getCommunityId());
        if (evaluation.getIsSatisfied() != 0) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "仅不满意评价需要跟进");
        }
        UnsatisfiedFollowup followup = new UnsatisfiedFollowup();
        followup.setEvaluationId(evaluationId);
        followup.setHandlerId(SecurityUtils.getUserId());
        followup.setFollowupContent(dto.getContent());
        followup.setFollowupTime(LocalDateTime.now());
        followupMapper.insert(followup);

        FollowUpVO vo = FollowUpVO.from(followup);
        SysUser handler = sysUserMapper.selectById(followup.getHandlerId());
        if (handler != null) {
            vo.setHandlerName(handler.getRealName());
        }
        log.info("不满意评价已跟进：evaluationId={}, operator={}", evaluationId, SecurityUtils.getUserId());
        return vo;
    }

    public List<FollowUpVO> followups(Long evaluationId) {
        requireEvaluation(evaluationId);
        return followupMapper.selectList(new LambdaQueryWrapper<UnsatisfiedFollowup>()
                        .eq(UnsatisfiedFollowup::getEvaluationId, evaluationId)
                        .orderByAsc(UnsatisfiedFollowup::getId))
                .stream().map(f -> {
                    FollowUpVO vo = FollowUpVO.from(f);
                    SysUser handler = sysUserMapper.selectById(f.getHandlerId());
                    if (handler != null) {
                        vo.setHandlerName(handler.getRealName());
                    }
                    return vo;
                }).toList();
    }

    /** 评价统计：总评价数 + 平均分 + 满意率 + 分档分布（C9 看板复用） */
    public Map<String, Object> statistics(Long communityId) {
        LambdaQueryWrapper<WorkOrderEvaluation> base = new LambdaQueryWrapper<WorkOrderEvaluation>()
                .eq(communityId != null, WorkOrderEvaluation::getCommunityId, communityId);
        Long total = evaluationMapper.selectCount(base);
        if (total == 0) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("total", 0L);
            empty.put("averageRating", 0.0);
            empty.put("satisfiedRate", 0.0);
            empty.put("ratingDistribution", Map.of());
            return empty;
        }
        List<WorkOrderEvaluation> all = evaluationMapper.selectList(base);
        double average = all.stream().mapToInt(WorkOrderEvaluation::getRating).average().orElse(0);
        long satisfied = all.stream().filter(e -> e.getIsSatisfied() == 1).count();
        Map<Integer, Long> distribution = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        WorkOrderEvaluation::getRating, java.util.stream.Collectors.counting()));

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("averageRating", Math.round(average * 10) / 10.0);
        result.put("satisfiedRate", Math.round(satisfied * 1000.0 / total) / 10.0);
        result.put("ratingDistribution", distribution);
        return result;
    }

    public WorkOrderEvaluation requireEvaluation(Long id) {
        WorkOrderEvaluation evaluation = evaluationMapper.selectById(id);
        if (evaluation == null) {
            throw new ResourceNotFoundException("评价不存在");
        }
        return evaluation;
    }

    private EvaluationVO toVO(WorkOrderEvaluation evaluation) {
        EvaluationVO vo = EvaluationVO.from(evaluation);
        WorkOrder order = workOrderMapper.selectById(evaluation.getWorkOrderId());
        if (order != null) {
            vo.setWorkOrderNo(order.getOrderNo());
        }
        Resident resident = residentMapper.selectById(evaluation.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        return vo;
    }
}
