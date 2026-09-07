package com.community.residence.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员绑定社区摘要 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员绑定的社区")
public class BoundCommunityVO {

    @Schema(description = "社区ID")
    private Long communityId;

    @Schema(description = "社区名称")
    private String communityName;

    @Schema(description = "社区地址")
    private String communityAddress;

    @Schema(description = "绑定时间")
    private String boundAt;
}
