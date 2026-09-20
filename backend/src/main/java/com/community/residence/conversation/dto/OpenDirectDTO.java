package com.community.residence.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 创建/获取直通会话请求（R63：居民与本社区管理员，幂等） */
@Data
@Schema(description = "创建/获取直通会话请求")
public class OpenDirectDTO {

    @Schema(description = "社区ID")
    @NotNull(message = "社区ID不能为空")
    private Long communityId;
}
