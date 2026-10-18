package com.community.residence.common.community.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.service.ResourceTimeslotService;
import com.community.residence.common.community.vo.TimeSlotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 资源时段管理控制器（接口设计.md 9.1.6） */
@Tag(name = "资源时段管理", description = "公共资源可预约时段管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ResourceTimeslotController {

    private final ResourceTimeslotService timeslotService;

    @Operation(summary = "资源时段列表（分页）")
    @GetMapping("/resources/{resourceId}/timeslots")
    public ApiResponse<PageVO<TimeSlotVO>> pageByResource(@PathVariable Long resourceId,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "20") long size,
                                                          @RequestParam(required = false) Integer dayOfWeek) {
        return ApiResponse.success(timeslotService.pageByResource(resourceId, page, size, dayOfWeek));
    }
}
