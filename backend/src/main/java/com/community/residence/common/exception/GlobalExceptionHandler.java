package com.community.residence.common.exception;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLTimeoutException;
import java.util.UUID;

/**
 * 全局异常处理（架构设计.md §9.2 / 接口设计.md §3.1 的映射实现）。
 * 业务异常返回 HTTP 200 + 5xxx；HTTP 层异常返回对应状态码；
 * 5xx 不向前端暴露堆栈，仅返回 traceId 供排查。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ---- 业务异常：HTTP 200 + 业务错误码 ---- */

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return ApiResponse.error(e.getErrorCode(), e.getMessage());
    }

    /* ---- 404：资源不存在 ---- */

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        log.info("资源不存在：uri={}, message={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    /* ---- 401：未认证 ---- */

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException e) {
        log.warn("认证失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    /* ---- 403：无权限（越权尝试 100% 记日志，含 userId 语义信息与来源 IP） ---- */

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException e, HttpServletRequest request) {
        log.error("越权尝试：uri={}, ip={}, message={}", request.getRequestURI(), clientIp(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(e.getErrorCode(), e.getMessage()));
    }

    /* ---- 400：参数校验 ---- */

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null
                ? String.format("参数[%s]%s", fieldError.getField(), fieldError.getDefaultMessage())
                : ErrorCode.INVALID_PARAM.getMessage();
        log.warn("参数校验失败：{}", message);
        return badRequest(message);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null
                ? String.format("参数[%s]%s", fieldError.getField(), fieldError.getDefaultMessage())
                : ErrorCode.INVALID_PARAM.getMessage();
        log.warn("参数绑定失败：{}", message);
        return badRequest(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String message = e.getAllErrors().isEmpty()
                ? ErrorCode.INVALID_PARAM.getMessage()
                : e.getAllErrors().get(0).getDefaultMessage();
        log.warn("参数校验失败：{}", message);
        return badRequest(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        String message = String.format("缺少必填参数[%s]", e.getParameterName());
        log.warn("缺少请求参数：{}", message);
        return badRequest(message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = String.format("参数[%s]类型不合法", e.getName());
        log.warn("参数类型不匹配：{}", message);
        return badRequest(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读：{}", e.getMessage());
        return badRequest("请求体格式不合法");
    }

    /* ---- 404：无对应路由（落到静态资源处理器） ---- */

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e, HttpServletRequest request) {
        log.info("路由不存在：{}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, "接口不存在"));
    }

    /* ---- 403：@PreAuthorize 功能级权限拒绝（越权尝试 100% 记日志） ---- */

    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthorizationDenied(
            org.springframework.security.authorization.AuthorizationDeniedException e, HttpServletRequest request) {
        log.error("功能级越权尝试：uri={}, ip={}, message={}", request.getRequestURI(), clientIp(request), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return badRequest(e.getMessage());
    }

    /* ---- 409：唯一约束冲突（转换为业务措辞，避免泄露表结构） ---- */

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("唯一约束冲突：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.DUPLICATE_RESOURCE));
    }

    /* ---- 503：数据库超时 ---- */

    @ExceptionHandler(SQLTimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleSqlTimeout(SQLTimeoutException e) {
        log.error("数据库超时", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE));
    }

    /* ---- 500：兜底，不暴露堆栈 ---- */

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        log.error("系统内部错误：traceId={}, uri={}", traceId, request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.internalError(traceId));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM, message));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}
