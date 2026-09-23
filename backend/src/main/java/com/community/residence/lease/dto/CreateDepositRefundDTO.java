package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 发起押金退还请求（R72）：渠道由服务端按实缴支付单推导（原路退回），
 * 请求体不接受客户端指定渠道；`channel` 仅用于一致性校验（与实缴渠道不一致即拒绝 5705）。
 */
@Data
@Schema(description = "发起押金退还请求")
public class CreateDepositRefundDTO {

    @Schema(description = "退还金额；为空 = 全额退还（实缴押金 − 已成功退还金额），由服务端计算")
    @DecimalMin(value = "0.01", message = "退还金额必须大于 0")
    private BigDecimal amount;

    @Schema(description = "备注（如抵扣说明、线下沟通结论；随租约变更历史留痕）")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;

    @Schema(description = "幂等键：商户退款请求号（同号重复提交返回既有结果，失败重试须换新号）")
    @NotBlank(message = "退款请求号不能为空")
    @Size(max = 64, message = "退款请求号最多 64 字符")
    private String outRequestNo;

    @Schema(description = "可选的渠道一致性校验值：与实缴支付渠道不一致时拒绝（不改变实际退款渠道）")
    @Size(max = 20, message = "渠道长度非法")
    private String channel;
}
