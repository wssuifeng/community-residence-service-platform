package com.community.residence.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 发起租约续约支付请求（金额服务端计算，客户端不可传金额） */
@Data
@Schema(description = "发起租约续约支付请求")
public class CreateRenewPaymentDTO {

    @Schema(description = "续租月数（1~36）")
    @NotNull(message = "续租月数不能为空")
    @Min(value = 1, message = "续租月数至少 1 个月")
    @Max(value = 36, message = "续租月数最多 36 个月")
    private Integer months;

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    @NotBlank(message = "支付渠道不能为空")
    @Pattern(regexp = "^(ALIPAY|WECHAT)$", message = "支付渠道不合法")
    private String channel;
}
