package com.community.residence.common.payment.vo;

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

    @Schema(description = "配置是否完整；已配置但停用不能显示未配置")
    private boolean configured;

    public static PaymentChannelVO of(String channel, boolean enabled) {
        return of(channel, enabled, enabled);
    }

    public static PaymentChannelVO of(String channel, boolean enabled, boolean configured) {
        PaymentChannelVO vo = new PaymentChannelVO();
        vo.setChannel(channel);
        vo.setEnabled(enabled);
        vo.setConfigured(configured);
        return vo;
    }
}
