package com.community.residence.common.exception;

import com.community.residence.common.constant.ErrorCode;
import lombok.Getter;

/**
 * 业务异常：业务逻辑错误（状态流转非法、预约冲突等）。
 * 统一映射为 HTTP 200 + 5xxx 业务错误码（接口设计.md §3.1）。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 覆盖默认提示（如携带具体冲突时段、状态等上下文） */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
