package com.community.residence.common.exception;

import com.community.residence.common.result.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEF-024 修复回归：multipart 层超限异常映射 400 + 明确提示
 * （原落入 500 兜底「系统内部错误」，业务层 10MB 提示对超限文件不可达）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("上传超限异常映射修复回归（DEF-024）")
class MaxUploadSizeHandlerFixTest {

    @InjectMocks
    private GlobalExceptionHandler handler;

    @Test
    @DisplayName("MaxUploadSizeExceededException → 400 + 上限提示（原 500）")
    void maxUploadSize_mapsTo400() {
        MaxUploadSizeExceededException e =
                new MaxUploadSizeExceededException(12L * 1024 * 1024);

        ResponseEntity<ApiResponse<Void>> response = handler.handleMaxUploadSize(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(400);
        assertThat(response.getBody().getMessage()).contains("10MB");
    }
}
