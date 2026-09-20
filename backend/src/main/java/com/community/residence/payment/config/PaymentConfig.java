package com.community.residence.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.community.residence.payment.service.channel.AlipayChannelClient;
import com.community.residence.payment.service.channel.DisabledChannelClient;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.service.channel.WechatChannelClient;

/**
 * 支付渠道客户端装配：凭据齐全装配对应实现，缺失装配 Disabled
 * （enabled()=false，发起支付抛「该支付渠道未配置」业务异常）。
 * SDK 客户端在装配期一次性构建（AlipayClient 线程安全可复用），
 * 凭据配置错误在启动日志 WARN 明示而非启动失败（渠道禁用不阻断，决策日志口径）。
 */
@Slf4j
@Configuration
public class PaymentConfig {

    /** 支付宝渠道客户端（凭据齐全时） */
    @Bean
    public PaymentChannelClient alipayChannelClient(PaymentProperties properties) {
        if (properties.getAlipay().isConfigured()) {
            return new AlipayChannelClient(properties);
        }
        log.warn("支付渠道 ALIPAY 凭据未配置，该渠道禁用（payment.alipay.* 四项齐全才启用）");
        return new DisabledChannelClient("ALIPAY");
    }

    /** 微信渠道客户端（凭据齐全时） */
    @Bean
    public PaymentChannelClient wechatChannelClient(PaymentProperties properties) {
        if (properties.getWechat().isConfigured()) {
            return new WechatChannelClient(properties);
        }
        log.warn("支付渠道 WECHAT 凭据未配置，该渠道禁用（payment.wechat.* 五项齐全才启用）");
        return new DisabledChannelClient("WECHAT");
    }
}
