package com.community.residence.common.payment.service.channel;

import com.community.residence.common.payment.constant.PaymentChannel;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.service.AlipayChannelConfigService;
import java.math.BigDecimal;

/** 数据库配置按版本刷新，停用时不解密/构建 SDK，也不使用环境变量回退。 */
public class ManagedAlipayChannelClient implements PaymentChannelClient {
    private final AlipayChannelConfigService configService;
    private final PaymentChannelClient disabled = DisabledChannelClient.alipay();
    private volatile Cached cached;
    private record Cached(long revision, PaymentChannelClient client) { }
    public ManagedAlipayChannelClient(AlipayChannelConfigService configService) { this.configService = configService; }
    @Override public String channel() { return PaymentChannel.ALIPAY; }
    @Override public boolean configured() { return configService.configured(); }
    @Override public boolean enabled() { return delegate().enabled(); }
    @Override public String createPayment(LeasePayment payment, String subject, String notifyUrl) { return delegate().createPayment(payment, subject, notifyUrl); }
    @Override public ChannelTradeResult queryTrade(String paymentNo) { return delegate().queryTrade(paymentNo); }
    @Override public ChannelRefundResult refund(LeasePayment payment, String requestNo, BigDecimal amount, String reason) { return delegate().refund(payment, requestNo, amount, reason); }
    private synchronized PaymentChannelClient delegate() {
        var config = configService.runtimeConfig();
        if (config == null) { cached = null; return disabled; }
        if (cached == null || cached.revision() != config.revision()) {
            cached = new Cached(config.revision(), new AlipayChannelClient(config.properties()));
        }
        return cached.client();
    }
}
