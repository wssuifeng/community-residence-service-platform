package com.community.residence.staff.workorder.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.staff.workorder.service.StaffWorkbenchService;
import com.community.residence.staff.workorder.vo.StaffWorkbenchVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
/** 当前服务人员工作台只读汇总。 */
@RestController
@RequestMapping("/api/v1/staff/workbench")
@RequiredArgsConstructor
@Tag(name="服务人员工作台")
public class StaffWorkbenchController {
    private final StaffWorkbenchService service;
    @GetMapping("/summary")
    @PreAuthorize("hasRole('STAFF')")
    @Operation(summary="读取本人工作台当前负荷与待办")
    public ApiResponse<StaffWorkbenchVO> summary() { return ApiResponse.success(service.summary()); }
}
