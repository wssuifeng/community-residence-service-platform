package com.community.residence.messaging.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.result.PageVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.messaging.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 通知中心控制器：P1 HTTP 轮询，P2 升级 WebSocket（接口设计.md 9.11） */
@Tag(name = "消息通知中心", description = "通知查询与已读管理接口")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "通知列表（分页）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ApiResponse<PageVO<NotificationVO>> page(@RequestParam(defaultValue = "1") long page,
                                                    @RequestParam(defaultValue = "20") long size,
                                                    @RequestParam(required = false) Boolean isRead) {
        return ApiResponse.success(notificationService.page(page, size, isRead));
    }

    @Operation(summary = "未读通知列表", description = "P1 轮询端点")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/unread")
    public ApiResponse<List<NotificationVO>> unread() {
        return ApiResponse.success(notificationService.unread());
    }

    @Operation(summary = "增量拉取通知", description = "按 seq 游标拉取（断线补拉）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/pull")
    public ApiResponse<List<NotificationVO>> pull(@RequestParam(required = false) Long sinceSeq) {
        return ApiResponse.success(notificationService.pull(sinceSeq));
    }

    @Operation(summary = "标记已读")
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable Long id) {
        notificationService.markRead(id);
        return ApiResponse.success();
    }

    @Operation(summary = "全部标记已读")
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        notificationService.markAllRead();
        return ApiResponse.success();
    }
}
