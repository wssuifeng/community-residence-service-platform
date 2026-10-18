package com.community.residence.common.conversation.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
/** 有限消息窗口及首次阅读定位，不修改原消息模型。 */
@Schema(description="消息窗口及阅读定位")
public record ConversationMessageWindowVO(
 @Schema(description="正序消息") List<?> records,
 @Schema(description="首次窗口从第一条未读开始") boolean startAtUnread,
 @Schema(description="第一条未读ID，仅首次有未读时返回") Long firstUnreadMessageId,
 @Schema(description="仍有更早消息") boolean hasOlder,
 @Schema(description="仍有更新消息，可按afterId补读") boolean hasNewer) {}
