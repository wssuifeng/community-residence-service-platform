package com.community.residence.common.exception;

import com.community.residence.common.constant.ErrorCode;

/** 未认证异常：未登录、令牌过期或已吊销。映射 HTTP 401 */
public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
