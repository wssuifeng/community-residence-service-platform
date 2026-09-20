package com.community.residence.payment.service.channel;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.payment.entity.LeasePayment;

/** 未配置渠道的占位客户端：渠道禁用（enabled=false），发起支付抛业务异常明示 */
public class DisabledChannelClient implements PaymentChannelClient {

    private final String channel;

    public DisabledChannelClient(String channel) {
        this.channel = channel;
    }

    @Override
    public String channel() {
        return channel;
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String createPayment(LeasePayment payment, String subject, String notifyUrl) {
        throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
    }

    @Override
    public ChannelTradeResult queryTrade(String paymentNo) {
        throw new BusinessException(ErrorCode.PAYMENT_CHANNEL_DISABLED);
    }

    /** 便捷工厂（PaymentConfig 装配用） */
    public static DisabledChannelClient alipay() {
        return new DisabledChannelClient(PaymentChannel.ALIPAY);
    }

    public static DisabledChannelClient wechat() {
        return new DisabledChannelClient(PaymentChannel.WECHAT);
    }
}
