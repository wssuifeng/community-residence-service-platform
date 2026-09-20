package com.community.residence.payment.service.channel;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.entity.LeasePayment;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 支付宝渠道客户端（电脑网站支付 alipay.trade.page.pay，
 * product_code=FAST_INSTANT_TRADE_PAY，pageExecute 生成跳转表单）。
 * 查单 alipay.trade.query，TRADE_SUCCESS/TRADE_FINISHED 均视为支付成功
 * （TRADE_FINISHED 为交易完结后不可退款态，属成功终态）。
 */
@Slf4j
public class AlipayChannelClient implements PaymentChannelClient {

    /** 交易成功状态（支付宝 trade_status：TRADE_SUCCESS 交易成功 / TRADE_FINISHED 交易完结） */
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String TRADE_FINISHED = "TRADE_FINISHED";

    /** SDK 客户端（线程安全，装配期一次构建复用） */
    private final AlipayClient alipayClient;
    private final PaymentProperties.Alipay config;

    public AlipayChannelClient(PaymentProperties properties) {
        this.config = properties.getAlipay();
        AlipayConfig alipayConfig = new AlipayConfig();
        alipayConfig.setServerUrl(config.getGatewayUrl());
        alipayConfig.setAppId(config.getAppId());
        alipayConfig.setPrivateKey(config.getMerchantPrivateKey());
        alipayConfig.setAlipayPublicKey(config.getAlipayPublicKey());
        alipayConfig.setFormat("json");
        alipayConfig.setCharset("UTF-8");
        alipayConfig.setSignType("RSA2");
        AlipayClient client;
        try {
            client = new DefaultAlipayClient(alipayConfig);
        } catch (AlipayApiException e) {
            /* 凭据格式错误在装配期暴露：降级为不可用客户端，渠道禁用而非启动失败 */
            log.error("支付宝客户端构建失败（凭据格式错误），渠道将不可用", e);
            client = null;
        }
        this.alipayClient = client;
    }

    @Override
    public String channel() {
        return PaymentChannel.ALIPAY;
    }

    @Override
    public boolean enabled() {
        return alipayClient != null;
    }

    @Override
    public String createPayment(LeasePayment payment, String subject, String notifyUrl) {
        /* 电脑网站支付：biz_content 装配 + return_url 回跳；pageExecute 返回整页跳转表单 HTML */
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(payment.getPaymentNo());
        model.setTotalAmount(payment.getAmount().toPlainString());
        model.setSubject(subject);
        model.setProductCode("FAST_INSTANT_TRADE_PAY");

        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        request.setReturnUrl(config.getReturnUrl());
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            request.setNotifyUrl(notifyUrl);
        }
        try {
            AlipayTradePagePayResponse response = alipayClient.pageExecute(request);
            if (!response.isSuccess()) {
                log.warn("支付宝下单失败：paymentNo={}, code={}, msg={}",
                        payment.getPaymentNo(), response.getCode(), response.getMsg());
                throw new ChannelCallException("支付宝下单失败：" + response.getMsg());
            }
            return response.getBody();
        } catch (AlipayApiException e) {
            log.error("支付宝下单异常：paymentNo={}", payment.getPaymentNo(), e);
            throw new ChannelCallException("支付宝下单异常：" + e.getErrMsg(), e);
        }
    }

    @Override
    public ChannelTradeResult queryTrade(String paymentNo) {
        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
        model.setOutTradeNo(paymentNo);
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        request.setBizModel(model);
        try {
            AlipayTradeQueryResponse response = alipayClient.execute(request);
            if (!response.isSuccess()) {
                /* ACQ.TRADE_NOT_EXIST 等业务码=渠道侧查无此单（尚未支付），
                   与网络异常同样返回 null 由调用方保留 PENDING */
                log.debug("支付宝查单未查到有效交易：paymentNo={}, subCode={}",
                        paymentNo, response.getSubCode());
                return null;
            }
            String tradeStatus = response.getTradeStatus();
            if (TRADE_SUCCESS.equals(tradeStatus) || TRADE_FINISHED.equals(tradeStatus)) {
                return new ChannelTradeResult(response.getTradeNo(), toDateTime(response.getSendPayDate()));
            }
            /* WAIT_BUYER_PAY 等中间态：未支付完成 */
            log.debug("支付宝交易未完成：paymentNo={}, tradeStatus={}", paymentNo, tradeStatus);
            return null;
        } catch (AlipayApiException e) {
            /* 渠道网络异常：由调用方吞掉保留 PENDING（此处转运行时异常上抛语义） */
            log.warn("支付宝查单异常：paymentNo={}", paymentNo, e);
            throw new ChannelCallException("支付宝查单异常：" + e.getErrMsg(), e);
        }
    }

    private LocalDateTime toDateTime(Date date) {
        return date != null
                ? LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault())
                : LocalDateTime.now();
    }
}
