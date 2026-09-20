package com.community.residence.workorder.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.workorder.dto.BatchSaveScheduleDTO;
import com.community.residence.workorder.dto.ClearScheduleDTO;
import com.community.residence.workorder.service.StaffScheduleService;
import com.community.residence.workorder.vo.BatchSaveScheduleResultVO;
import com.community.residence.workorder.vo.StaffScheduleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** 服务人员排班控制器：批量设置/查询/清空（V19，C4 物业调度） */
@Tag(name = "服务人员排班", description = "服务人员班次排班接口")
@RestController
@RequestMapping("/api/v1/staff-schedules")
@RequiredArgsConstructor
public class StaffScheduleController {

    private final StaffScheduleService staffScheduleService;

    @Operation(summary = "排班列表", description = "按社区与日期范围查询（跨度上限 62 天），日期升序、人员升序")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping
    public ApiResponse<List<StaffScheduleVO>> list(
            @RequestParam(required = false) Long communityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) Long staffId) {
        return ApiResponse.success(staffScheduleService.list(communityId, startDate, endDate, staffId));
    }

    @Operation(summary = "批量设置排班", description = "人员×日期逐条覆盖式保存；同社区同人同日已存在则更新")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/batch")
    public ApiResponse<BatchSaveScheduleResultVO> batchSave(@RequestBody @Valid BatchSaveScheduleDTO dto) {
        return ApiResponse.success(staffScheduleService.batchSave(dto));
    }

    @Operation(summary = "删除单条排班")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        staffScheduleService.delete(id);
        return ApiResponse.success();
    }

    @Operation(summary = "清空范围排班", description = "communityId/startDate/endDate 必填；staffIds 逗号分隔可空（空=全部人员）")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping
    public ApiResponse<BatchSaveScheduleResultVO> clear(@Valid ClearScheduleDTO dto) {
        return ApiResponse.success(staffScheduleService.clear(dto));
    }
}
