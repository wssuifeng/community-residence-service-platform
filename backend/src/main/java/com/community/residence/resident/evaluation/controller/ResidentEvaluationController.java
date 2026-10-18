package com.community.residence.resident.evaluation.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.evaluation.dto.CreateEvaluationDTO;
import com.community.residence.common.evaluation.service.EvaluationService;
import com.community.residence.common.evaluation.vo.EvaluationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 评价管理控制器：工单评价 + 不满意跟进 + 统计（接口设计.md 9.8） */
@Tag(name = "评价管理", description = "服务评价与不满意跟进接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResidentEvaluationController {

    private final EvaluationService evaluationService;

    @Operation(summary = "提交评价", description = "工单须已完成；一单一评")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping("/work-orders/{orderId}/evaluation")
    public ApiResponse<EvaluationVO> create(@PathVariable Long orderId,
                                            @RequestBody @Valid CreateEvaluationDTO dto) {
        return ApiResponse.success(evaluationService.create(orderId, dto));
    }
}
