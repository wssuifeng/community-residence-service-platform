package com.community.residence.conversation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 建/取租约沟通会话请求（DEF-076）。
 * 审批通过内核（ResidenceApplicationService）已自动建群并回写 `lease_record.conversation_id`；
 * 本请求用于**存量/旁路数据缺会话时由管理端手动补建**，或在会话中心按租约直接进入——
 * 幂等：同租约已存在 LEASE_GROUP 直接返回既有会话，不重复建群、不重复入群。
 */
@Data
@Schema(description = "建/取租约沟通会话请求")
public class EnsureLeaseGroupDTO {

    @Schema(description = "租约ID")
    @NotNull(message = "租约ID不能为空")
    private Long leaseId;
}
