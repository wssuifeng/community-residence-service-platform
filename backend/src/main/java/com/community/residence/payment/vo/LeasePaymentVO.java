package com.community.residence.payment.vo;

import com.community.residence.payment.entity.LeasePayment;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租约支付单响应（R61 续期 + R71 首期同表不同型；渠道凭据按渠道选择性返回：ALIPAY→alipayForm，WECHAT→qrCode） */
@Data
@Schema(description = "租约支付单")
public class LeasePaymentVO {

    @JsonIgnore
    @Schema(description = "支付记录主键，仅用于服务端操作留痕", hidden = true)
    private Long id;

    @Schema(description = "业务单号（首期 FIRST+yyyyMMdd+6位随机；续期 RENEW+yyyyMMdd+6位随机）")
    private String paymentNo;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "支付单类型（R71）：FIRST-首期（首月租金+押金）, RENEWAL-续期（月租×月数）")
    private String paymentType;

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    private String channel;

    @Schema(description = "支付月数（首期固定 1，续期为居民所选月数）")
    private Integer months;

    @Schema(description = "应付金额（首期=月租+押金；续期=月租×月数；均由服务端计算）")
    private BigDecimal amount;

    @Schema(description = "状态：PENDING-待支付, SUCCESS-已支付, CLOSED-已关闭")
    private String status;

    @Schema(description = "支付宝跳转表单 HTML（仅 ALIPAY 且 PENDING 时返回，前端写入页面自动提交跳转支付宝收银台）")
    private String alipayForm;

    @Schema(description = "微信 Native 支付二维码链接 code_url（仅 WECHAT 且 PENDING 时返回，前端生成二维码扫码支付）")
    private String qrCode;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "支付完成时间")
    private LocalDateTime paidAt;

    @Schema(description = "渠道流水号（R73：渠道返回的交易号/退款归属交易号，便于对账核对）")
    private String channelTradeNo;

    @Schema(description = "金额构成·租金部分（R73：首期=月租，续期=月租×月数；服务端保证 amount = rentAmount + depositAmount）")
    private BigDecimal rentAmount;

    @Schema(description = "金额构成·押金部分（R73：首期=押金，续期=0）")
    private BigDecimal depositAmount;

    @Schema(description = "覆盖租期开始（R73：首期=租期开始日，续期=本轮顺延起点）")
    private java.time.LocalDate periodStart;

    @Schema(description = "覆盖租期结束（R73：首期=租期开始日+1 个月，续期=顺延后止期）")
    private java.time.LocalDate periodEnd;

    public static LeasePaymentVO from(LeasePayment entity) {
        LeasePaymentVO vo = new LeasePaymentVO();
        vo.setId(entity.getId());
        vo.setPaymentNo(entity.getPaymentNo());
        vo.setLeaseId(entity.getLeaseId());
        vo.setPaymentType(entity.getPaymentType());
        vo.setChannel(entity.getChannel());
        vo.setMonths(entity.getMonths());
        vo.setAmount(entity.getAmount());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setPaidAt(entity.getPaidAt());
        vo.setChannelTradeNo(entity.getChannelTradeNo());
        return vo;
    }
}
