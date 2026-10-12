package com.community.residence.common.exception;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEF-099 修复回归 + 标准错误码规范落地（接口设计.md §3.3）。
 *
 * 背景：框架层本应返回 405（方法不支持）/ 415（内容类型不支持）的请求，
 * 原被全局异常处理器兜底包成 **500「系统内部错误」** ⇒ 错误语义丢失、排障困难。
 * 本轮新增通用请求错误段（405/415/400/4001），并保证：
 * ① HTTP 状态码保持语义正确（405 就是 405、415 就是 415）；
 * ② 响应体仍是统一 ApiResponse（标准业务码 + 可读 message）；
 * ③ 禁止落入 500 兜底。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("框架级请求错误映射回归（DEF-099）")
class FrameworkRequestErrorMappingTest {

    @InjectMocks
    private GlobalExceptionHandler handler;

    /** 处理器读取请求 URI 仅用于日志留痕；单元测试给出最小可用桩 */
    private HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/probe");
        return request;
    }

    @Test
    @DisplayName("① 方法不支持 → 405 + 405 标准码（原为 500「系统内部错误」）")
    void methodNotSupported_mapsTo405() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("GET", Set.of("POST"));

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMethodNotSupported(e, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(405);
        assertThat(response.getBody().getCode())
                .isEqualTo(ErrorCode.REQUEST_METHOD_NOT_ALLOWED.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("请求方法不被支持");
    }

    @Test
    @DisplayName("② 内容类型不支持 → 415 + 415 标准码（原为 500）")
    void mediaTypeNotSupported_mapsTo415() {
        HttpMediaTypeNotSupportedException e =
                new HttpMediaTypeNotSupportedException("text/plain", java.util.List.of());

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMediaTypeNotSupported(e, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(415);
        assertThat(response.getBody().getCode())
                .isEqualTo(ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("请求内容类型不被支持");
    }

    @Test
    @DisplayName("③ 请求体不可读 → 400 + 400 标准码，且不落入 500")
    void unreadableBody_mapsTo400() {
        HttpMessageNotReadableException e =
                new HttpMessageNotReadableException("JSON parse error", null, null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadable(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(response.getBody().getCode())
                .isEqualTo(ErrorCode.REQUEST_BODY_MALFORMED.getCode());
        // DEF-A 契约：文案保持「可行动」
        assertThat(response.getBody().getMessage())
                .isEqualTo("请求体缺失或格式不正确，请提交合法的 JSON 请求体");
    }

    @Test
    @DisplayName("④ 路由不存在 → 404 + 4001 标准码（原业务体 404-接口不存在）")
    void noResourceFound_mapsTo404WithStandardCode() {
        NoResourceFoundException e = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/api/v1/not-exists", "/api/v1/not-exists");

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleNoResourceFound(e, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(4001);
        assertThat(response.getBody().getCode())
                .isEqualTo(ErrorCode.REQUEST_PATH_NOT_FOUND.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo("接口不存在");
    }

    @Test
    @DisplayName("⑤ 无处理器 404（NoHandlerFoundException）与静态资源 404 同码同语义")
    void noHandlerFound_mapsTo404WithStandardCode() {
        NoHandlerFoundException e =
                new NoHandlerFoundException("GET", "/api/v1/not-exists", null);

        ResponseEntity<ApiResponse<Void>> response = handler.handleNoHandlerFound(e, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode())
                .isEqualTo(ErrorCode.REQUEST_PATH_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("⑥ 4xxx 通用请求错误段取值 = HTTP 状态码，且每个码都有可读 message")
    void requestErrorSegment_isConsistent() {
        assertThat(ErrorCode.REQUEST_METHOD_NOT_ALLOWED.getCode()).isEqualTo(405);
        assertThat(ErrorCode.REQUEST_MEDIA_TYPE_UNSUPPORTED.getCode()).isEqualTo(415);
        assertThat(ErrorCode.REQUEST_BODY_MALFORMED.getCode()).isEqualTo(400);
        assertThat(ErrorCode.REQUEST_PATH_NOT_FOUND.getCode()).isEqualTo(4001);

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getCode()).as("错误码 %s 取值必须为正整数", code.name()).isPositive();
            assertThat(code.getMessage()).as("错误码 %s 必须有可读 message", code.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("⑦ 标准错误码规范：常量名唯一；400 语义由请求体码承载（与既有 HTTP 层 400 同值）")
    void errorCodes_haveUniqueNames() {
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        for (ErrorCode code : ErrorCode.values()) {
            Integer previous = seen.put(code.name(), code.getCode());
            assertThat(previous)
                    .as("常量 %s 重复定义（%d 与 %d）", code.name(), previous, code.getCode())
                    .isNull();
        }
        // INVALID_PARAM 为 §3.1 既有 HTTP 层码（参数校验），REQUEST_BODY_MALFORMED 为本轮
        // 「请求体不可读」语义码——两者同为 400，前端按 400 分流，message 区分具体原因。
        assertThat(ErrorCode.REQUEST_BODY_MALFORMED.getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM.getCode());
    }
}
