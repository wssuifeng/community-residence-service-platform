package com.community.residence.common.lease.entity;
import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.*;
import java.math.BigDecimal;
@Data @TableName("lease_extension_application")
@Schema(description="租期延长申请快照")
public class LeaseExtensionApplication {
    @TableId(type=IdType.AUTO)
    @Schema(description="申请ID") private Long id;
    @Schema(description="租约ID") private Long leaseId;
    @Schema(description="社区ID") private Long communityId;
    @Schema(description="主租客账号ID") private Long residentId;
    @Schema(description="延长月数") private Integer months;
    @Schema(description="原合同止期快照") private LocalDate originalEndDate;
    @Schema(description="拟延长止期快照") private LocalDate proposedEndDate;
    @Schema(description="沿用月租快照") private BigDecimal monthlyRent;
    @Schema(description="沿用押金快照，不重复收取") private BigDecimal deposit;
    @Schema(description="申请理由") private String reason;
    @Schema(description="PENDING/APPROVED/REJECTED/WITHDRAWN") private String status;
    @Schema(description="审核管理员账号ID") private Long reviewBy;
    @Schema(description="审核时间") private LocalDateTime reviewedAt;
    @Schema(description="审核说明") private String reviewNote;
    @Schema(description="申请时间") private LocalDateTime createdAt;
}
