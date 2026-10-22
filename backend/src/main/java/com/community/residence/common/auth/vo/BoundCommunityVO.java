package com.community.residence.common.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 管理员绑定小区摘要 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员绑定的小区")
public class BoundCommunityVO {

    @Schema(description = "小区ID")
    private Long communityId;

    @Schema(description = "小区名称")
    private String communityName;

    @Schema(description = "小区地址")
    private String communityAddress;

    @Schema(description = "绑定时间")
    private String boundAt;
}
