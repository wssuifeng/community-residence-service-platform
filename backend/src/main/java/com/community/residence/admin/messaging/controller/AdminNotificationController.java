package com.community.residence.admin.messaging.controller;

import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.messaging.dto.ChannelLevelsDTO;
import com.community.residence.common.messaging.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 通知中心控制器：P1 HTTP 轮询，P2 升级 WebSocket（接口设计.md 9.11） */
@Tag(name = "消息通知中心", description = "通知查询与已读管理接口")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "查询渠道分级配置", description = "R51；站内 WEBSOCKET 必达不在配置范围")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @GetMapping("/channel-levels")
    public ApiResponse<Map<String, List<String>>> getChannelLevels() {
        return ApiResponse.success(notificationService.readChannelLevels());
    }

    @Operation(summary = "更新渠道分级配置", description = "R51；渠道分级为全局级配置，仅超级管理员（定义 v1.3 权责边界）")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @PutMapping("/channel-levels")
    public ApiResponse<Void> updateChannelLevels(@RequestBody @Valid ChannelLevelsDTO dto) {
        notificationService.updateChannelLevels(dto.getLevels());
        return ApiResponse.success();
    }
}
