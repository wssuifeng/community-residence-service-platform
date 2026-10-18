package com.community.residence.common.lease.entity;
import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.time.*;
@Data @TableName("lease_contract_term") @Schema(description="合同段与续租协议快照")
public class LeaseContractTerm {
    @TableId(type=IdType.AUTO) @Schema(description="合同段ID") private Long id;
    @Schema(description="租约ID") private Long leaseId;
    @Schema(description="INITIAL或RENEWAL") private String termType;
    @Schema(description="开始日期") private LocalDate startDate;
    @Schema(description="结束日期，含当天") private LocalDate endDate;
    @Schema(description="合同月数") private Integer contractMonths;
    @Schema(description="首次支付月数") private Integer firstPaymentMonths;
    @Schema(description="后续周期月数") private Integer paymentCycleMonths;
    @Schema(description="段月租快照") private BigDecimal monthlyRent;
    @Schema(description="本段新收押金，续租为0") private BigDecimal deposit;
    @Schema(description="OFFERED管理方已发出/SIGNED居民已同意/ACTIVE已生效/CANCELLED取消") private String status;
    @Schema(description="不可变协议正文") private String contentSnapshot;
    @Schema(description="居民确认时间") private LocalDateTime confirmedAt;
    @Schema(description="创建时间") private LocalDateTime createdAt;
}
