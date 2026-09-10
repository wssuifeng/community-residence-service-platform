package com.community.residence.notice.vo;

import com.community.residence.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 公告信息响应 */
@Data
@Schema(description = "公告信息")
public class NoticeVO {

    @Schema(description = "公告ID")
    private Long id;

    @Schema(description = "目标社区ID（首个社区目标；全系统广播时为空）")
    private Long communityId;

    @Schema(description = "目标社区名称（首个社区目标）")
    private String communityName;

    @Schema(description = "目标范围列表（R25 v1.2：多社区/楼栋定向）")
    private List<TargetItem> targets;

    @Schema(description = "公告标题")
    private String title;

    @Schema(description = "公告内容")
    private String content;

    @Schema(description = "状态：DRAFT-草稿, PUBLISHED-已发布, WITHDRAWN-已撤回")
    private String status;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "失效时间")
    private LocalDateTime endTime;

    @Schema(description = "浏览次数")
    private Integer viewCount;

    @Schema(description = "置顶：0-普通, 1-置顶（R25 v1.2）")
    private Integer isPinned;

    @Schema(description = "发布人ID")
    private Long publisherId;

    @Schema(description = "发布人姓名")
    private String publisherName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static NoticeVO from(Notice entity) {
        NoticeVO vo = new NoticeVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setStatus(entity.getStatus());
        vo.setPublishTime(entity.getPublishTime());
        vo.setEndTime(entity.getEndTime());
        vo.setViewCount(entity.getViewCount());
        vo.setIsPinned(entity.getIsPinned());
        vo.setPublisherId(entity.getPublisherId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 目标范围项（R25：COMMUNITY 社区 / BUILDING 楼栋） */
    @Data
    @Schema(description = "公告目标范围")
    public static class TargetItem {

        @Schema(description = "目标类型：COMMUNITY-社区, BUILDING-楼栋")
        private String targetType;

        @Schema(description = "目标ID")
        private Long targetId;

        @Schema(description = "目标名称（社区名/楼栋名，可空）")
        private String targetName;

        public static TargetItem of(String targetType, Long targetId, String targetName) {
            TargetItem item = new TargetItem();
            item.setTargetType(targetType);
            item.setTargetId(targetId);
            item.setTargetName(targetName);
            return item;
        }
    }
}
