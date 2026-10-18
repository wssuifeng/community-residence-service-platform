package com.community.residence.common.conversation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 会话参与者概要（R68 会话详情：群成员展示用，最小暴露面仅 ID/姓名/角色/身份空间） */
@Data
@Schema(description = "会话参与者概要")
public class ConversationParticipantVO {

    @Schema(description = "参与者ID（ownerType=RESIDENT 时为 resident.id，USER 时为 sys_user.id）")
    private Long userId;

    @Schema(description = "参与者身份空间：USER=sys_user 主体 / RESIDENT=resident 主体（DEF-075：与 userId 成对使用，两空间主键可撞号）")
    private String ownerType;

    @Schema(description = "参与者姓名")
    private String name;

    @Schema(description = "参与者角色：RESIDENT/STAFF/ADMIN/SUPER_ADMIN")
    private String role;

    public static ConversationParticipantVO of(Long userId, String ownerType, String name, String role) {
        ConversationParticipantVO vo = new ConversationParticipantVO();
        vo.setUserId(userId);
        vo.setOwnerType(ownerType);
        vo.setName(name);
        vo.setRole(role);
        return vo;
    }
}
