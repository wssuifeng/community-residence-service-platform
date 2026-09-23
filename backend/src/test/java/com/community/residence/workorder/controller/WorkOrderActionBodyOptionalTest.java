package com.community.residence.workorder.controller;

import com.community.residence.common.exception.GlobalExceptionHandler;
import com.community.residence.workorder.dto.WorkOrderActionDTO;
import com.community.residence.workorder.dto.WorkOrderRemarkDTO;
import com.community.residence.workorder.service.WorkOrderService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DEF-A 回归：工单处置端点「请求体可选」契约。
 *
 * 背景（浏览器级 E2E 抓包实证）：`PATCH /work-orders/{id}/confirm` 与 `/close` 原声明
 * `@RequestBody` 必填，而前端 api 层把 body 声明为可选、调用点（居民/管理端工单详情）
 * 确实不传 body ⇒ HttpMessageNotReadableException ⇒ 400「请求体格式不合法」，
 * 导致「确认完成」「关闭工单」不可达、CF-E8 评价链被硬阻断。
 *
 * 覆盖：① 不传 body confirm 成功 ② 不传 body close 成功 ③ body 畸形仍是**可读** 400
 * ④ 备注超长仍走字段校验（@Valid 未失效）⑤ 必填理由动作的 @NotBlank 语义未丢失。
 * 权限（@PreAuthorize 功能级 + 服务层数据级）由 WorkOrderServiceTest 的越权用例覆盖。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkOrderController 请求体可选回归（DEF-A）")
class WorkOrderActionBodyOptionalTest {

    @Mock
    private WorkOrderService workOrderService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkOrderController(workOrderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("DEF-A ①：确认完成不传请求体 → 200，服务层收到缺省备注（原为 400 阻断）")
    void confirm_withoutBody_succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).confirm(1L, null);
    }

    @Test
    @DisplayName("DEF-A ②：关闭工单不传请求体 → 200，服务层收到缺省备注（原为 400 阻断）")
    void close_withoutBody_succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/2/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).close(2L, null);
    }

    @Test
    @DisplayName("DEF-A 补充：接单/开始处理不传请求体同样放行（前端 api 同声明为可选）")
    void acceptAndProcess_withoutBody_succeeds() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/3/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(patch("/api/v1/work-orders/3/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(workOrderService).accept(3L, null);
        verify(workOrderService).process(3L, null);
    }

    @Test
    @DisplayName("带备注调用时备注照常透传（不因可选而丢字段）")
    void confirm_withRemark_passesRemarkThrough() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"已确认，感谢师傅\"}"))
                .andExpect(status().isOk());

        verify(workOrderService).confirm(1L, "已确认，感谢师傅");
    }

    @Test
    @DisplayName("DEF-A ③：请求体畸形 → 400 且给出可行动的提示（不再是「请求体格式不合法」）")
    void malformedBody_readableBadRequest() throws Exception {
        mockMvc.perform(patch("/api/v1/work-orders/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体缺失或格式不正确，请提交合法的 JSON 请求体"));
    }

    @Test
    @DisplayName("DEF-A ④：可选请求体上的字段校验未失效——备注超 500 字仍 400 且提示具体上限")
    void oversizedRemark_stillRejected() throws Exception {
        String longRemark = "x".repeat(501);
        mockMvc.perform(patch("/api/v1/work-orders/1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"" + longRemark + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("处置说明最多 500 字符")));
    }

    @Test
    @DisplayName("DEF-A ⑤：必填理由动作的 @NotBlank 语义未丢失（完成处理/退回/驳回/取消）")
    void requiredReasonDto_stillEnforcesNotBlank() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new WorkOrderActionDTO()))
                .singleElement()
                .satisfies(v -> assertThat(v.getMessage()).isEqualTo("处置说明不能为空"));
        assertThat(validator.validate(new WorkOrderRemarkDTO())).isEmpty();
    }
}
