package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 入住申请审批通过请求（自动建立居住关系与租住记录） */
@Data
@Schema(description = "入住申请审批通过请求")
public class ApproveApplicationDTO {

    @Schema(description = "租期开始日期")
    @NotNull(message = "租期开始日期不能为空")
    private LocalDate leaseStartDate;

    @Schema(description = "租期结束日期")
    @NotNull(message = "租期结束日期不能为空")
    private LocalDate leaseEndDate;

    @Schema(description = "月租金")
    @NotNull(message = "月租金不能为空")
    @DecimalMin(value = "0", message = "月租金不能为负数")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    @Schema(description = "审核意见")
    @Size(max = 500, message = "审核意见最多 500 字符")
    private String remark;
}
