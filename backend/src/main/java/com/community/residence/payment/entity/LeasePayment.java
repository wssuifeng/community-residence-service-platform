package com.community.residence.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 租约续约支付单（lease_payment 表；状态机 PENDING → SUCCESS / CLOSED） */
@Data
@TableName("lease_payment")
@Schema(description = "租约续约支付单")
public class LeasePayment {

    @TableId(type = IdType.AUTO)
    @Schema(description = "支付单ID")
    private Long id;

    @Schema(description = "业务单号（RENEW+yyyyMMdd+6位随机）")
    private String paymentNo;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "付款人ID（resident.id）")
    private Long residentId;

    @Schema(description = "支付渠道：ALIPAY-支付宝, WECHAT-微信")
    private String channel;

    @Schema(description = "续租月数")
    private Integer months;

    @Schema(description = "应付金额（月租×月数，服务端计算）")
    private BigDecimal amount;

    @Schema(description = "状态：PENDING-待支付, SUCCESS-已支付, CLOSED-已关闭")
    private String status;

    @Schema(description = "渠道流水号（支付成功后回写）")
    private String channelTradeNo;

    @Schema(description = "支付完成时间")
    private LocalDateTime paidAt;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
