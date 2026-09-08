package com.community.residence.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 管理员-社区绑定请求 */
@Data
@Schema(description = "管理员-社区绑定请求")
public class BindCommunityDTO {

    @Schema(description = "社区ID")
    @NotNull(message = "社区ID不能为空")
    private Long communityId;
}
