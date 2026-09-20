package com.community.residence.payment.service.channel;

/**
 * 渠道调用失败运行时异常：SDK 检查型异常（AlipayApiException）与微信
 * 运行时异常的统一包装。发起支付场景直接对用户可见（关单+提示稍后重试），
 * 查单场景由 PaymentService 吞掉保留 PENDING。
 */
public class ChannelCallException extends RuntimeException {

    public ChannelCallException(String message) {
        super(message);
    }

    public ChannelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
