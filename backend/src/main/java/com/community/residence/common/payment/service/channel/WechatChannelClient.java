package com.community.residence.common.payment.service.channel;

import com.community.residence.common.payment.constant.PaymentChannel;
import com.community.residence.common.payment.config.PaymentProperties;
import com.community.residence.common.payment.entity.LeasePayment;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.service.payments.model.Transaction;
import com.wechat.pay.java.service.payments.nativepay.NativePayService;
import com.wechat.pay.java.service.payments.nativepay.model.Amount;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayRequest;
import com.wechat.pay.java.service.payments.nativepay.model.PrepayResponse;
import com.wechat.pay.java.service.payments.nativepay.model.QueryOrderByOutTradeNoRequest;
import com.wechat.pay.java.service.refund.RefundService;
import com.wechat.pay.java.service.refund.model.AmountReq;
import com.wechat.pay.java.service.refund.model.CreateRequest;
import com.wechat.pay.java.service.refund.model.Refund;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * 微信支付渠道客户端（Native 下单 v3 /v3/pay/transactions/native → code_url 二维码）。
 * 凭据：商户号 + appid + APIv3 密钥 + 证书序列号 + 商户私钥（内容或路径）；
 * RSAAutoCertificateConfig 自动拉取并轮换平台证书（含 apiV3Key）。
 * 查单 GET /v3/pay/transactions/out-trade-no/{no}，tradeState=SUCCESS 视为支付成功。
 * 退款 POST /v3/refund/domestic/refunds（R72）：out_refund_no 为商户退款请求号（幂等键），
 * status=SUCCESS/PROCESSING 视为渠道已受理。
 */
@Slf4j
public class WechatChannelClient implements PaymentChannelClient {

    /** SDK 服务（装配期一次构建复用） */
    private final NativePayService nativePayService;
    private final RefundService refundService;
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
        NativePayService payService;
        RefundService refund;
        try {
            /* 一次构建配置供下单与退款两个服务复用（自动证书管理实例可共享） */
            RSAAutoCertificateConfig sdkConfig = builder.build();
            payService = new NativePayService.Builder().config(sdkConfig).build();
            /* 退款服务构建失败不应连带禁用支付：单独兜底 */
            RefundService built;
            try {
                built = new RefundService.Builder().config(sdkConfig).build();
            } catch (Exception e) {
                log.error("微信退款服务构建失败，退款能力不可用（支付仍可用）", e);
                built = null;
            }
            refund = built;
        } catch (Exception e) {
            /* 凭据/证书错误在装配期暴露：降级为不可用客户端，渠道禁用而非启动失败 */
            log.error("微信支付客户端构建失败（凭据/证书错误），渠道将不可用", e);
            payService = null;
            refund = null;
        }
        this.nativePayService = payService;
        this.refundService = refund;
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
        if(payment.getChannelExpiresAt()!=null)request.setTimeExpire(payment.getChannelExpiresAt().atOffset(java.time.ZoneOffset.ofHours(8)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
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

    /**
     * 微信退款（POST /v3/refund/domestic/refunds，R72 押金原路退还）：out_trade_no 定位原交易，
     * out_refund_no 为商户退款请求号（幂等键）；total 须为原订单总额（分）。
     * status=SUCCESS 记渠道退款单号 refund_id；PROCESSING（已受理在途）同样按受理成功处理
     * （本地无公网回调，后续核对走渠道查询；接口设计 §9.20.1 口径为「渠道返回成功」）；
     * 响应无 status（null）按失败处理——无法确认已退款不得当成功落账（DEF-128）。
     */
    @Override
    public ChannelRefundResult refund(LeasePayment payment, String outRequestNo,
                                      BigDecimal amount, String reason) {
        if (refundService == null) {
            throw new ChannelCallException("微信退款服务不可用（退款能力未装配）");
        }
        CreateRequest request = new CreateRequest();
        request.setOutTradeNo(payment.getPaymentNo());
        request.setOutRefundNo(outRequestNo);
        if (reason != null && !reason.isBlank()) {
            request.setReason(reason);
        }
        /* 金额单位为分（refund 为本单退款额，total 为原订单总额）：先收敛到 2 位小数再转分，
           避免服务端计算值超精度时 movePointRight 后 longValueExact 抛裸 ArithmeticException（DEF-128） */
        long refundFen = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long totalFen = payment.getAmount().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        AmountReq refundAmount = new AmountReq();
        refundAmount.setRefund(refundFen);
        refundAmount.setTotal(totalFen);
        refundAmount.setCurrency("CNY");
        request.setAmount(refundAmount);
        try {
            Refund refund = refundService.create(request);
            /* 渠道返回空状态按失败处理（DEF-128）：null 无法确认已退款，不得当成功落账 */
            if (refund.getStatus() == null) {
                log.warn("微信退款响应无状态，按失败处理：outRequestNo={}, refundId={}",
                        outRequestNo, refund.getRefundId());
                return ChannelRefundResult.failure("微信退款响应无状态，无法确认退款结果");
            }
            if (refund.getStatus() == com.wechat.pay.java.service.refund.model.Status.SUCCESS) {
                return ChannelRefundResult.success(refund.getRefundId());
            }
            if (refund.getStatus() == com.wechat.pay.java.service.refund.model.Status.PROCESSING) {
                log.info("微信退款已受理在途：outRequestNo={}, refundId={}", outRequestNo, refund.getRefundId());
                return ChannelRefundResult.success(refund.getRefundId());
            }
            log.warn("微信退款未成功：outRequestNo={}, status={}", outRequestNo, refund.getStatus());
            return ChannelRefundResult.failure("微信退款未成功：渠道状态 " + refund.getStatus());
        } catch (Exception e) {
            log.error("微信退款异常：paymentNo={}, outRequestNo={}", payment.getPaymentNo(), outRequestNo, e);
            throw new ChannelCallException("微信退款异常：" + e.getMessage(), e);
        }
    }
}
