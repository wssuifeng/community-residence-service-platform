package com.community.residence.resident.housing.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.common.housing.service.ViewingAppointmentService;
import com.community.residence.common.housing.vo.ViewingAppointmentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/** 看房预约管理控制器：居民预约 + 管理端处置（接口设计.md 9.12.2） */
@Tag(name = "看房预约管理", description = "看房预约提交与处置接口")
@RestController
@RequestMapping("/api/v1/viewing-appointments")
@RequiredArgsConstructor
public class ResidentViewingAppointmentController {

    private final ViewingAppointmentService appointmentService;

    @Operation(summary = "创建看房预约", description = "仅注册并登录的居民账号可提交；服务端从令牌绑定预约人身份")
    @PreAuthorize("hasRole('RESIDENT')")
    @PostMapping
    public ApiResponse<ViewingAppointmentVO> create(@RequestBody @Valid CreateViewingAppointmentDTO dto) {
        return ApiResponse.success(appointmentService.create(dto));
    }
}
