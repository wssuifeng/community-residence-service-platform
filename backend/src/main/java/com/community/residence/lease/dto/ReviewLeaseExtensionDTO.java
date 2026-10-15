package com.community.residence.lease.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data @Schema(description="批准或驳回原方案，不允许暗改租期和费用")
public class ReviewLeaseExtensionDTO {
 @Schema(description="是否批准") @NotNull private Boolean approve;
 @Schema(description="审核说明，驳回必填") @Size(max=500) private String reason;
}
