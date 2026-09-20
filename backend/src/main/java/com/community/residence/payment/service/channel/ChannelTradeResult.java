package com.community.residence.payment.service.channel;

import java.time.LocalDateTime;

/** 渠道查单成功结果（支付完成时的渠道侧信息） */
public record ChannelTradeResult(String channelTradeNo, LocalDateTime paidAt) {
}
