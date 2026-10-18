package com.community.residence.common.payment.constant;

/** 支付环境与官方网关固定映射，配置接口不接受任意网络地址。 */
public enum PaymentEnvironment {
    SANDBOX("https://openapi-sandbox.dl.alipaydev.com/gateway.do"),
    PRODUCTION("https://openapi.alipay.com/gateway.do");
    private final String alipayGateway;
    PaymentEnvironment(String alipayGateway) { this.alipayGateway = alipayGateway; }
    public String alipayGateway() { return alipayGateway; }
}
