package com.community.residence.notice.entity;
import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("notice_revision") @Schema(description="不可变公告版本快照")
public class NoticeRevision {
 @TableId(type=IdType.AUTO) @Schema(description="记录ID") private Long id;
 @Schema(description="公告ID") private Long noticeId;
 @Schema(description="正文版本") private Integer contentVersion;
 @Schema(description="标题快照") private String title;
 @Schema(description="纯文本摘要快照") private String content;
 @Schema(description="受限JSON正文") private String bodyJson;
 @Schema(description="历史图片引用") private String images;
 @Schema(description="编辑管理员ID") private Long editorId;
 @Schema(description="版本保存时间") private LocalDateTime editedAt;
}
