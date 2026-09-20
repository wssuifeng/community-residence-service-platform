package com.community.residence.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 支付渠道凭据配置（R61：凭据齐全才启用渠道，缺任一项该渠道禁用并明示）。
 * 密钥类配置经环境变量注入，默认空 = 渠道禁用；生产同理由环境注入，不入库不入库房。
 */
@Data
@Component
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    /** 支付宝渠道配置（沙箱：网关 openapi-sandbox.dl.alipaydev.com + 测试买家账号可开通） */
    private Alipay alipay = new Alipay();

    /** 微信支付渠道配置（无公开沙箱：需商户号 + API 证书；Native 下单） */
    private Wechat wechat = new Wechat();

    /** 公网部署时的支付结果异步通知地址（本地以主动查单替代回调，见 PaymentController 预留注释） */
    private String notifyUrl;

    @Data
    public static class Alipay {
        /** 支付宝开放平台应用 APPID */
        private String appId;
        /** 网关（沙箱 openapi-sandbox.dl.alipaydev.com，生产 openapi.alipay.com） */
        private String gatewayUrl;
        /** 应用私钥（PKCS8） */
        private String merchantPrivateKey;
        /** 支付宝公钥（非应用公钥） */
        private String alipayPublicKey;
        /** 支付完成后前端回跳地址 */
        private String returnUrl;

        /** 凭据齐全才启用渠道 */
        public boolean isConfigured() {
            return hasText(appId) && hasText(gatewayUrl)
                    && hasText(merchantPrivateKey) && hasText(alipayPublicKey);
        }
    }

    @Data
    public static class Wechat {
        /** 商户号 mchid */
        private String mchid;
        /** 应用 appid（公众号/小程序/应用绑定） */
        private String appid;
        /** APIv3 密钥 */
        private String apiV3Key;
        /** 商户 API 证书序列号 */
        private String merchantSerialNumber;
        /** 商户 API 私钥（apiclient_key.pem 内容，与 privateKeyPath 二选一） */
        private String privateKey;
        /** 商户 API 私钥文件路径（与 privateKey 二选一） */
        private String privateKeyPath;

        /** 凭据齐全才启用渠道（私钥内容与路径二选一） */
        public boolean isConfigured() {
            boolean keyReady = hasText(privateKey) || hasText(privateKeyPath);
            return hasText(mchid) && hasText(appid) && hasText(apiV3Key)
                    && hasText(merchantSerialNumber) && keyReady;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
