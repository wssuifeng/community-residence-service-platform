package com.community.residence.payment.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 支付渠道状态（凭据未配置时 enabled=false，渠道禁用并明示） */
@Data
@Schema(description = "支付渠道状态")
public class PaymentChannelVO {

    @Schema(description = "渠道标识：ALIPAY-支付宝, WECHAT-微信")
    private String channel;

    @Schema(description = "是否启用（凭据配置齐全才启用）")
    private boolean enabled;

    public static PaymentChannelVO of(String channel, boolean enabled) {
        PaymentChannelVO vo = new PaymentChannelVO();
        vo.setChannel(channel);
        vo.setEnabled(enabled);
        return vo;
    }
}
