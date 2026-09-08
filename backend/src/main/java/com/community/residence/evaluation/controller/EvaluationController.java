package com.community.residence.evaluation.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.evaluation.dto.CreateEvaluationDTO;
import com.community.residence.evaluation.dto.CreateFollowUpDTO;
import com.community.residence.evaluation.service.EvaluationService;
import com.community.residence.evaluation.vo.EvaluationVO;
import com.community.residence.evaluation.vo.FollowUpVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 评价管理控制器：工单评价 + 不满意跟进 + 统计（接口设计.md 9.8） */
@Tag(name = "评价管理", description = "服务评价与不满意跟进接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @Operation(summary = "提交评价", description = "工单须已完成；一单一评")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/work-orders/{orderId}/evaluation")
    public ApiResponse<EvaluationVO> create(@PathVariable Long orderId,
                                            @RequestBody @Valid CreateEvaluationDTO dto) {
        return ApiResponse.success(evaluationService.create(orderId, dto));
    }

    @Operation(summary = "查询工单评价")
    @PreAuthorize("hasAnyRole('RESIDENT', 'STAFF', 'ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/work-orders/{orderId}/evaluation")
    public ApiResponse<EvaluationVO> getByWorkOrder(@PathVariable Long orderId) {
        return ApiResponse.success(evaluationService.getByWorkOrder(orderId));
    }

    @Operation(summary = "评价列表（分页）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/evaluations")
    public ApiResponse<PageVO<EvaluationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) Integer minRating,
                                                  @RequestParam(required = false) Integer maxRating) {
        return ApiResponse.success(evaluationService.page(page, size, minRating, maxRating));
    }

    @Operation(summary = "不满意评价列表", description = "跟进工作台")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/evaluations/unsatisfied")
    public ApiResponse<PageVO<EvaluationVO>> unsatisfied(@RequestParam(defaultValue = "1") long page,
                                                         @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(evaluationService.unsatisfied(page, size));
    }

    @Operation(summary = "添加跟进", description = "仅不满意评价可跟进")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/evaluations/{evaluationId}/followup")
    public ApiResponse<FollowUpVO> addFollowup(@PathVariable Long evaluationId,
                                               @RequestBody @Valid CreateFollowUpDTO dto) {
        return ApiResponse.success(evaluationService.addFollowup(evaluationId, dto));
    }

    @Operation(summary = "跟进记录列表")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/evaluations/{evaluationId}/followups")
    public ApiResponse<List<FollowUpVO>> followups(@PathVariable Long evaluationId) {
        return ApiResponse.success(evaluationService.followups(evaluationId));
    }

    @Operation(summary = "评价统计", description = "总数/平均分/满意率/分档分布")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/statistics/evaluations")
    public ApiResponse<Map<String, Object>> statistics(@RequestParam(required = false) Long communityId) {
        return ApiResponse.success(evaluationService.statistics(communityId));
    }
}
