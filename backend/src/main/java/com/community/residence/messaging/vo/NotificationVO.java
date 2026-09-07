package com.community.residence.messaging.vo;

import com.community.residence.messaging.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 通知响应 */
@Data
@Schema(description = "通知")
public class NotificationVO {

    @Schema(description = "通知ID")
    private Long id;

    @Schema(description = "全局递增序号（增量拉取游标）")
    private Long seq;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "通知标题")
    private String title;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "通知类型")
    private String type;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源ID")
    private Long sourceId;

    @Schema(description = "已读状态：0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static NotificationVO from(Notification entity) {
        NotificationVO vo = new NotificationVO();
        vo.setId(entity.getId());
        vo.setSeq(entity.getSeq());
        vo.setCommunityId(entity.getCommunityId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setType(entity.getType());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setIsRead(entity.getIsRead());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
