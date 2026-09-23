package com.community.residence.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建/获取直通会话请求（R63：居民与本社区管理员，幂等）。
 *
 * DEF-076：`residentId` 为**管理端代居民发起**时的会话对象——
 * 居民本人调用可省略（后端强制取当前登录居民，传他人一律拒绝）；
 * ADMIN/SUPER_ADMIN 调用必填（否则 5001 参数错误），即管理端会话中心「开启会话」。
 */
@Data
@Schema(description = "创建/获取直通会话请求")
public class OpenDirectDTO {

    @Schema(description = "社区ID")
    @NotNull(message = "社区ID不能为空")
    private Long communityId;

    @Schema(description = "会话对象居民ID（管理端代居民发起时必填；居民本人调用可省略）")
    private Long residentId;
}
