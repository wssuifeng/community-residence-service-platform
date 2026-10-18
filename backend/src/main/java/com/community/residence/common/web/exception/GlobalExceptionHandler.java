package com.community.residence.common.web.exception;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.result.ApiResponse;
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
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.sql.SQLTimeoutException;
import java.util.UUID;

/**
 * 全局异常处理（架构设计.md §9.2 / 接口设计.md §3.1 §3.3 的映射实现）。
 * 业务异常返回 HTTP 200 + 5xxx；HTTP 层异常返回对应状态码；
 * 5xx 不向前端暴露堆栈，仅返回 traceId 供排查。
 *
 * 标准错误码规范落地（2026-09-23，DEF-099）：框架级请求错误
 * （方法不支持 / 媒体类型不支持 / 请求体不可读 / 路由不存在）一律按
 * **语义正确的 HTTP 状态码**（405 / 415 / 400 / 404）返回统一 ApiResponse，
 * 禁止落入 500 兜底。安全行为不回退：`@PreAuthorize` 拒绝仍为 403、认证失败仍为 401。
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

    /* ---- 框架级请求错误（DEF-099）：按语义 HTTP 状态码返回统一 ApiResponse ----
       这些异常发生在进入业务代码之前，原实现落入 500 兜底 ⇒ 错误语义丢失、排障困难。
       标准（接口设计.md §3.3）：405 / 415 / 400 / 404 保持 HTTP 语义正确。 */

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持：uri={}, method={}, supported={}",
                request.getRequestURI(), e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.REQUEST_METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("请求内容类型不支持：uri={}, contentType={}, supported={}",
                request.getRequestURI(), e.getContentType(), e.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED));
    }

    /* ---- 404：无对应处理器（未开启 throw-exception-if-no-handler-found 时兜底） ---- */

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(
            NoHandlerFoundException e, HttpServletRequest request) {
        log.info("路由不存在：uri={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.REQUEST_PATH_NOT_FOUND));
    }

    /* ---- 400：请求体不可读（DEF-A：原文案「请求体格式不合法」对调用方不可行动——
       既说不清是缺 body 还是 JSON 写错，也看不出该怎么改；改为说明「缺什么/怎么办」。
       请求体是否必填由各端点 @RequestBody(required) 决定，本处理器只兜"读不出来"） ---- */

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读（不记录解析原文，避免泄露凭据）");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.REQUEST_BODY_MALFORMED));
    }

    /* ---- 404：无对应路由（落到静态资源处理器） ---- */

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e, HttpServletRequest request) {
        log.info("路由不存在：{}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ErrorCode.REQUEST_PATH_NOT_FOUND));
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

    /* ---- 400：上传文件超限（DEF-024：multipart 层超限原落入 500 兜底，
       业务层「文档不能超过 10MB」提示对 >10MB 文件不可达；multipart 上限与
       业务上限间留 buffer 使业务层校验可达） ---- */

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAM, "单个文件不能超过 10MB"));
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
