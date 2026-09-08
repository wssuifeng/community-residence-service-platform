package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 续租请求（需求 E3：续租=顺延止期，非重新登记） */
@Data
@Schema(description = "续租请求")
public class RenewLeaseDTO {

    @Schema(description = "新结束日期（须晚于原结束日期）")
    @NotNull(message = "新结束日期不能为空")
    private LocalDate newEndDate;

    @Schema(description = "月租金")
    @NotNull(message = "月租金不能为空")
    @DecimalMin(value = "0.00", message = "月租金不能为负数")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    @NotNull(message = "押金不能为空")
    @DecimalMin(value = "0.00", message = "押金不能为负数")
    private BigDecimal deposit;

    @Schema(description = "备注（续租说明）")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
