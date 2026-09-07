package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 创建租住记录请求（管理员登记；社区归属由房屋推导） */
@Data
@Schema(description = "创建租住记录请求")
public class CreateLeaseDTO {

    @Schema(description = "租客ID")
    @NotNull(message = "租客不能为空")
    private Long residentId;

    @Schema(description = "房屋ID")
    @NotNull(message = "房屋不能为空")
    private Long houseId;

    @Schema(description = "租期开始日期")
    @NotNull(message = "租期开始日期不能为空")
    private LocalDate startDate;

    @Schema(description = "租期结束日期")
    @NotNull(message = "租期结束日期不能为空")
    private LocalDate endDate;

    @Schema(description = "月租金")
    @NotNull(message = "月租金不能为空")
    @DecimalMin(value = "0", message = "月租金不能为负数")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    @DecimalMin(value = "0", message = "押金不能为负数")
    private BigDecimal deposit;

    @Schema(description = "合同附件URL")
    @Size(max = 255, message = "合同附件URL最多 255 字符")
    private String contractUrl;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
