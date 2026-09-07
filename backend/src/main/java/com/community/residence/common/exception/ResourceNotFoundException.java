package com.community.residence.common.exception;

import com.community.residence.common.constant.ErrorCode;

/** 资源不存在异常：映射 HTTP 404 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
