package com.community.residence.common.lease.vo;

import com.community.residence.common.lease.entity.LeaseDepositRefund;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 押金退还记录响应（R72/§9.20.1 契约字段；同时供 R73 租约明细时间线的押金流水条目） */
@Data
@Schema(description = "押金退还记录")
public class DepositRefundVO {

    @Schema(description = "押金退还记录ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "关联退租申请ID（非退租场景为空）")
    private Long moveOutApplicationId;

    @Schema(description = "本次退还金额")
    private BigDecimal amount;

    @Schema(description = "退款渠道：ALIPAY / WECHAT")
    private String channel;

    @Schema(description = "商户退款请求号（幂等键）")
    private String outRequestNo;

    @Schema(description = "渠道退款单号")
    private String channelRefundNo;

    @Schema(description = "退款状态：PENDING-处理中, SUCCESS-已退还, FAILED-失败")
    private String status;

    @Schema(description = "失败原因（FAILED 时必填，前端必须可见）")
    private String failReason;

    @Schema(description = "发起人用户ID")
    private Long operatorId;

    @Schema(description = "发起人姓名")
    private String operatorName;

    @Schema(description = "发起时间")
    private LocalDateTime createdAt;

    @Schema(description = "退款完成时间（SUCCESS 时写）")
    private LocalDateTime refundedAt;

    public static DepositRefundVO from(LeaseDepositRefund entity) {
        DepositRefundVO vo = new DepositRefundVO();
        vo.setId(entity.getId());
        vo.setLeaseId(entity.getLeaseId());
        vo.setMoveOutApplicationId(entity.getMoveOutApplicationId());
        vo.setAmount(entity.getAmount());
        vo.setChannel(entity.getChannel());
        vo.setOutRequestNo(entity.getOutRequestNo());
        vo.setChannelRefundNo(entity.getChannelRefundNo());
        vo.setStatus(entity.getStatus());
        vo.setFailReason(entity.getFailReason());
        vo.setOperatorId(entity.getOperatorId());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setRefundedAt(entity.getRefundedAt());
        return vo;
    }
}
