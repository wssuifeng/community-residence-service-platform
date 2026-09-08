package com.community.residence.community.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateTimeSlotDTO;
import com.community.residence.community.service.ResourceTimeslotService;
import com.community.residence.community.vo.TimeSlotVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "创建资源时段", description = "按周循环模板，同资源同星期不允许重叠")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/resources/{resourceId}/timeslots")
    public ApiResponse<TimeSlotVO> create(@PathVariable Long resourceId,
                                          @RequestBody @Valid CreateTimeSlotDTO dto) {
        return ApiResponse.success(timeslotService.create(resourceId, dto));
    }

    @Operation(summary = "更新资源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/timeslots/{id}")
    public ApiResponse<TimeSlotVO> update(@PathVariable Long id, @RequestBody @Valid CreateTimeSlotDTO dto) {
        return ApiResponse.success(timeslotService.update(id, dto));
    }

    @Operation(summary = "删除资源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/timeslots/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        timeslotService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "资源时段列表（分页）")
    @GetMapping("/resources/{resourceId}/timeslots")
    public ApiResponse<PageVO<TimeSlotVO>> pageByResource(@PathVariable Long resourceId,
                                                          @RequestParam(defaultValue = "1") long page,
                                                          @RequestParam(defaultValue = "20") long size,
                                                          @RequestParam(required = false) Integer dayOfWeek) {
        return ApiResponse.success(timeslotService.pageByResource(resourceId, page, size, dayOfWeek));
    }
}
