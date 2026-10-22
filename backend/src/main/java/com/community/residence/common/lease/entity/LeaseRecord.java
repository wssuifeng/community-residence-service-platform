package com.community.residence.common.lease.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 租住记录（lease_record 表；「即将到期/已到期」为定时任务标注语义，非状态值） */
@Data
@TableName("lease_record")
@Schema(description = "租住记录")
public class LeaseRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "租住记录ID")
    private Long id;

    @Schema(description = "租客ID")
    private Long tenantId;

    @Schema(description = "所属小区ID")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "租期开始日期")
    private LocalDate startDate;

    @Schema(description = "租期结束日期")
    private LocalDate endDate;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    private BigDecimal deposit;

    @Schema(description = "押金状态（R72）：UNPAID-未缴, PAID-已缴, REFUNDING-退还中, REFUNDED-已退还, OFFSET-已抵扣")
    private String depositStatus;

    @Schema(description = "状态：PENDING-待审核, TO_SIGN-待签约, ACTIVE-已生效, MOVED_OUT-已搬出, ARCHIVED-已归档, REJECTED-已驳回")
    private String status;

    @Schema(description = "合同附件URL")
    private String contractUrl;

    @Schema(description = "协议签约状态：NONE-未发起, PENDING-待确认, PARTIAL-单方已确认, SIGNED-双方已确认, CANCELLED-已撤回")
    private String agreementStatus;

    @Schema(description = "历史承租确认时间（已停用，不作为协议确认或发出前置）")
    private LocalDateTime tenantConfirmAt;

    @Schema(description = "历史承租确认人（resident.id；仅保留历史数据）")
    private Long tenantConfirmBy;

    @Schema(description = "租约群聊ID（R68，conversation.id；审核通过自动建群后回写）")
    private Long conversationId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description="合同总月数；历史未知为空") private Integer contractMonths;
    @Schema(description="首期支付月数，新约默认1") private Integer firstPaymentMonths;
    @Schema(description="后续缴租周期月数，新约默认1") private Integer paymentCycleMonths;
}
