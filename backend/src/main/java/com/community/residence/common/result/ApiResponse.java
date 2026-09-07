package com.community.residence.common.result;

import com.community.residence.common.constant.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统一响应封装（接口设计.md §2：code/message/data/timestamp）。
 * 所有接口（含错误场景）必须返回本类型，禁止绕过。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一响应格式")
public class ApiResponse<T> {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Schema(description = "业务状态码：200 成功，400/401/403/404/409/500/503 HTTP 层错误，5xxx 业务错误", example = "200")
    private int code;

    @Schema(description = "响应消息：成功固定为 success，失败为具体错误信息", example = "success")
    private String message;

    @Schema(description = "业务数据，可为空")
    private T data;

    @Schema(description = "响应时间戳（ISO 8601）", example = "2026-09-07T15:30:00")
    private String timestamp;

    @Schema(description = "链路追踪 ID，仅 5xx 系统错误填充，供排查使用")
    private String traceId;

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data, LocalDateTime.now().format(TS), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return error(errorCode.getCode(), message);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, LocalDateTime.now().format(TS), null);
    }

    /** 5xx 兜底响应：不向前端暴露堆栈，仅返回 traceId */
    public static <T> ApiResponse<T> internalError(String traceId) {
        return new ApiResponse<>(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage(),
                null, LocalDateTime.now().format(TS), traceId);
    }
}
