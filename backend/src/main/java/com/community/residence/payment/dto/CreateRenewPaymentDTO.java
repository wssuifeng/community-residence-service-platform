package com.community.residence.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 发起合同内交租或续租首笔租金请求（金额服务端计算，客户端不可传金额） */
@Data
@Schema(description = "发起合同内交租或续租首笔租金请求")
public class CreateRenewPaymentDTO {

    @Schema(description = "兼容旧客户端的月数字段；不决定合同或收费月数，服务端以已确认合同段计算")
    @Min(value = 1, message = "月数如提供须为正整数")
    private Integer months;

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    @NotBlank(message = "支付渠道不能为空")
    @Pattern(regexp = "^(ALIPAY|WECHAT)$", message = "支付渠道不合法")
    private String channel;
}
