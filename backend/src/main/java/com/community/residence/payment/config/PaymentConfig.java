package com.community.residence.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.community.residence.payment.service.AlipayChannelConfigService;
import com.community.residence.payment.service.channel.ManagedAlipayChannelClient;
import com.community.residence.payment.service.channel.DisabledChannelClient;
import com.community.residence.payment.service.channel.PaymentChannelClient;
import com.community.residence.payment.service.channel.WechatChannelClient;

/**
 * 支付渠道客户端装配：支付宝由数据库独立配置按版本读取，本批保持停用；
 * 微信沿用环境凭据门控。支付宝不回退到旧环境变量，不在启动期读取私钥。
 */
@Slf4j
@Configuration
public class PaymentConfig {

    /** 支付宝凭据来源为超管独立配置，停用/缺失不回退到环境变量。 */
    @Bean
    public PaymentChannelClient alipayChannelClient(AlipayChannelConfigService configService) {
        return new ManagedAlipayChannelClient(configService);
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
