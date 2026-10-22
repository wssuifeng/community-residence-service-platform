package com.community.residence.common.conversation.entity;
import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("chat_message_image") @Schema(description="消息图片关联，权限由所属业务会话验证")
public class ChatMessageImage {
 @TableId(type=IdType.AUTO) @Schema(description="图片记录ID") private Long id;
 @Schema(description="小区ID，供级联清理") private Long communityId;
 @Schema(description="消息空间FEEDBACK/CONVERSATION/VIEWING") private String messageScope;
 @Schema(description="对应空间内消息ID") private Long messageId;
 @Schema(description="平台上传路径") private String fileUrl;
 @Schema(description="原文件名") private String fileName;
 @Schema(description="文件大小字节") private Long fileSize;
 @Schema(description="创建时间") private LocalDateTime createdAt;
}
