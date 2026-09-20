package com.community.residence.payment.vo;

import com.community.residence.payment.entity.LeasePayment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租约续约支付单响应（渠道凭据按渠道选择性返回：ALIPAY→alipayForm，WECHAT→qrCode） */
@Data
@Schema(description = "租约续约支付单")
public class LeasePaymentVO {

    @Schema(description = "业务单号（RENEW+yyyyMMdd+6位随机）")
    private String paymentNo;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    private String channel;

    @Schema(description = "续租月数")
    private Integer months;

    @Schema(description = "应付金额（月租×月数，服务端计算）")
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

    public static LeasePaymentVO from(LeasePayment entity) {
        LeasePaymentVO vo = new LeasePaymentVO();
        vo.setPaymentNo(entity.getPaymentNo());
        vo.setLeaseId(entity.getLeaseId());
        vo.setChannel(entity.getChannel());
        vo.setMonths(entity.getMonths());
        vo.setAmount(entity.getAmount());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setPaidAt(entity.getPaidAt());
        return vo;
    }
}
