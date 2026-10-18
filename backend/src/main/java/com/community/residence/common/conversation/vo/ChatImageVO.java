package com.community.residence.common.conversation.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
@Data @Schema(description="已发送图片消息内容")
public class ChatImageVO {
 @Schema(description="图片访问路径") private String fileUrl;
 @Schema(description="图片名称") private String fileName;
 @Schema(description="文件大小字节") private Long fileSize;
}
