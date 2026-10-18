package com.community.residence.common.web.exception;

import com.community.residence.common.web.constant.ErrorCode;

/** 无权限异常：角色不匹配或数据级权限拒绝。映射 HTTP 403，越权尝试 100% 记日志 */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
