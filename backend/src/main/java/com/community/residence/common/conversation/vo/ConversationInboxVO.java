package com.community.residence.common.conversation.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
/** 统一会话列表项，不替代原业务模型。 */
@Data
@Schema(description="统一会话列表项")
public class ConversationInboxVO {
 @Schema(description="来源CONVERSATION/FEEDBACK") private String source;
 @Schema(description="来源内ID") private Long id;
 @Schema(description="原会话类型或FEEDBACK") private String type;
 @Schema(description="标题") private String title;
 @Schema(description="原业务状态") private String status;
 @Schema(description="所属社区ID") private Long communityId;
 @Schema(description="所属社区名称") private String communityName;
 @Schema(description="原业务ID") private Long relatedId;
 @Schema(description="房屋四段编号；非房屋业务为空") private String houseCode;
 @Schema(description="反馈对端安全称呼，匿名不含身份") private String residentLabel;
 @Schema(description="最后消息摘要；反馈无消息取原始内容") private String lastMessage;
 @Schema(description="最后活动时间，不受查看影响") private LocalDateTime activityAt;
 @Schema(description="聊天未读数，不含本人发送") private Long unreadCount;
}
