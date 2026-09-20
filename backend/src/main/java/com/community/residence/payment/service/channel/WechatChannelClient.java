package com.community.residence.payment.service.channel;

import com.community.residence.common.constant.PaymentChannel;
import com.community.residence.payment.config.PaymentProperties;
import com.community.residence.payment.entity.LeasePayment;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 微信支付渠道客户端（Native 下单 v3 /v3/pay/transactions/native → code_url 二维码）。
 * 凭据：商户号 + appid + APIv3 密钥 + 证书序列号 + 商户私钥（内容或路径）；
 * RSAAutoCertificateConfig 自动拉取并轮换平台证书（含 apiV3Key）。
 * 查单 GET /v3/pay/transactions/out-trade-no/{no}，tradeState=SUCCESS 视为支付成功。
 */
@Slf4j
public class WechatChannelClient implements PaymentChannelClient {

    /** SDK 服务（装配期一次构建复用） */
    private final NativePayService nativePayService;
    private final PaymentProperties.Wechat config;

    public WechatChannelClient(PaymentProperties properties) {
        this.config = properties.getWechat();
        RSAAutoCertificateConfig.Builder builder = new RSAAutoCertificateConfig.Builder()
                .merchantId(config.getMchid())
                .merchantSerialNumber(config.getMerchantSerialNumber())
                .apiV3Key(config.getApiV3Key());
        if (config.getPrivateKey() != null && !config.getPrivateKey().isBlank()) {
            builder.privateKey(config.getPrivateKey());
        } else {
            builder.privateKeyFromPath(config.getPrivateKeyPath());
        }
        NativePayService service;
        try {
            service = new NativePayService.Builder().config(builder.build()).build();
        } catch (Exception e) {
            /* 凭据/证书错误在装配期暴露：降级为不可用客户端，渠道禁用而非启动失败 */
            log.error("微信支付客户端构建失败（凭据/证书错误），渠道将不可用", e);
            service = null;
        }
        this.nativePayService = service;
    }

    @Override
    public String channel() {
        return PaymentChannel.WECHAT;
    }

    @Override
    public boolean enabled() {
        return nativePayService != null;
    }

    @Override
    public String createPayment(LeasePayment payment, String subject, String notifyUrl) {
        /* Native 下单：金额单位为分（元×100 取整，系统金额两位小数无舍入损失） */
        PrepayRequest request = new PrepayRequest();
        request.setAppid(config.getAppid());
        request.setMchid(config.getMchid());
        request.setDescription(subject);
        request.setOutTradeNo(payment.getPaymentNo());
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            request.setNotifyUrl(notifyUrl);
        }
        Amount amount = new Amount();
        amount.setTotal(payment.getAmount().movePointRight(2).intValueExact());
        request.setAmount(amount);
        try {
            PrepayResponse response = nativePayService.prepay(request);
            return response.getCodeUrl();
        } catch (Exception e) {
            log.error("微信 Native 下单异常：paymentNo={}", payment.getPaymentNo(), e);
            throw new ChannelCallException("微信下单异常：" + e.getMessage(), e);
        }
    }

    @Override
    public ChannelTradeResult queryTrade(String paymentNo) {
        QueryOrderByOutTradeNoRequest request = new QueryOrderByOutTradeNoRequest();
        request.setMchid(config.getMchid());
        request.setOutTradeNo(paymentNo);
        try {
            Transaction transaction = nativePayService.queryOrderByOutTradeNo(request);
            if (Transaction.TradeStateEnum.SUCCESS == transaction.getTradeState()) {
                return new ChannelTradeResult(transaction.getTransactionId(),
                        toDateTime(transaction.getSuccessTime()));
            }
            /* NOTPAY/CLOSED/PAYERROR 等非成功态：未支付完成 */
            log.debug("微信交易未完成：paymentNo={}, tradeState={}",
                    paymentNo, transaction.getTradeState());
            return null;
        } catch (Exception e) {
            /* 渠道网络异常：由调用方吞掉保留 PENDING（此处转运行时异常上抛语义） */
            log.warn("微信查单异常：paymentNo={}", paymentNo, e);
            throw new ChannelCallException("微信查单异常：" + e.getMessage(), e);
        }
    }

    private LocalDateTime toDateTime(String rfc3339) {
        return rfc3339 != null ? OffsetDateTime.parse(rfc3339).toLocalDateTime() : LocalDateTime.now();
    }
}
