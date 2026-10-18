package com.community.residence.common.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金退还记录（lease_deposit_refund 表，R72；状态机 PENDING → SUCCESS / FAILED）。
 * 追加型留痕：每次发起退还写一条新记录，不覆盖历史；out_request_no 为幂等键。
 * 本表只承载退款指令与渠道结果，**不是资金账户**（无余额、无计息字段）。
 */
@Data
@TableName("lease_deposit_refund")
@Schema(description = "押金退还记录")
public class LeaseDepositRefund {

    @TableId(type = IdType.AUTO)
    @Schema(description = "押金退还记录ID")
    private Long id;

    @Schema(description = "租住记录ID")
    private Long leaseId;

    @Schema(description = "关联退租申请ID（非退租场景为空，弱关联）")
    private Long moveOutApplicationId;

    @Schema(description = "本次退还金额")
    private BigDecimal amount;

    @Schema(description = "退款渠道：ALIPAY-支付宝, WECHAT-微信（与原支付渠道一致）")
    private String channel;

    @Schema(description = "商户退款请求号（幂等键，同号重复提交返回既有结果）")
    private String outRequestNo;

    @Schema(description = "渠道退款单号（渠道返回后回写）")
    private String channelRefundNo;

    @Schema(description = "退款状态：PENDING-处理中, SUCCESS-已退还, FAILED-失败")
    private String status;

    @Schema(description = "失败原因（渠道返回或异常摘要，FAILED 时必填）")
    private String failReason;

    @Schema(description = "发起人用户ID（sys_user.id）")
    private Long operatorId;

    @Schema(description = "发起时间")
    private LocalDateTime createdAt;

    @Schema(description = "退款完成时间（SUCCESS 时写）")
    private LocalDateTime refundedAt;
}
