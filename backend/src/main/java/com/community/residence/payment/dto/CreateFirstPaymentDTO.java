package com.community.residence.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发起首期费用支付请求（R71）：金额与月数均由服务端按租约登记值计算
 * （首月租金 + 押金，月数固定 1），客户端仅选渠道，传金额无效。
 */
@Data
@Schema(description = "发起首期费用支付请求")
public class CreateFirstPaymentDTO {

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    @NotBlank(message = "支付渠道不能为空")
    @Pattern(regexp = "^(ALIPAY|WECHAT)$", message = "支付渠道不合法")
    private String channel;
}
