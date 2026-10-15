package com.community.residence.lease.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data @Schema(description="租期延长申请，不变更租金与押金")
public class LeaseExtensionDTO {
 @Schema(description="延长月数") @NotNull @Min(1) @Max(1200) private Integer months;
 @Schema(description="申请说明") @Size(max=500) private String reason;
}
