package com.community.residence.common.notice.vo;

import com.community.residence.common.notice.entity.Notice;
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

    @Schema(description = "目标小区ID（首个小区目标；全系统广播时为空）")
    private Long communityId;

    @Schema(description = "目标小区名称（首个小区目标）")
    private String communityName;

    @Schema(description = "目标范围列表（R25 v1.2：多小区/楼栋定向）")
    private List<TargetItem> targets;

    @Schema(description = "公告标题")
    private String title;

    @Schema(description = "公告内容")
    private String content;

    @Schema(description = "公告上传图片列表，无图返回空列表")
    private java.util.List<String> images;


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

    @Schema(description = "优先级：LOW-低, NORMAL-普通, HIGH-高, URGENT-紧急（V12）")
    private String priority;

    @Schema(description = "公告类型：ANNOUNCEMENT-公告（V12）")
    private String type;

    @Schema(description = "失效时间别名（前端表单字段名，与 endTime 同值）")
    private LocalDateTime expireTime;

    @Schema(description = "发布人ID")
    private Long publisherId;

    @Schema(description = "发布人姓名")
    private String publisherName;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description="受限正文JSON") private String bodyJson;
    @Schema(description="正文版本") private Integer contentVersion;
    @Schema(description="最后修订时间") private LocalDateTime editedAt;
    @Schema(description="最后修订管理员ID") private Long editedBy;

    public static NoticeVO from(Notice entity) {
        NoticeVO vo = new NoticeVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setBodyJson(entity.getBodyJson());vo.setContentVersion(entity.getContentVersion()==null?1:entity.getContentVersion());vo.setEditedAt(entity.getEditedAt());vo.setEditedBy(entity.getEditedBy());
        vo.setImages(entity.getImages() == null || entity.getImages().isBlank() ? java.util.List.of() : java.util.Arrays.asList(entity.getImages().split(",")));
        vo.setStatus(entity.getStatus());
        vo.setPublishTime(entity.getPublishTime());
        vo.setEndTime(entity.getEndTime());
        vo.setViewCount(entity.getViewCount());
        vo.setIsPinned(entity.getIsPinned());
        vo.setPriority(entity.getPriority());
        vo.setType(entity.getType());
        vo.setExpireTime(entity.getEndTime());
        vo.setPublisherId(entity.getPublisherId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 目标范围项（R25：COMMUNITY 小区 / BUILDING 楼栋 / GUEST 无需居住关系可见） */
    @Data
    @Schema(description = "公告目标范围")
    public static class TargetItem {

        @Schema(description = "目标类型：COMMUNITY-小区, BUILDING-楼栋, GUEST-无需居住关系可见（DEF-046）")
        private String targetType;

        @Schema(description = "目标ID（GUEST 目标为空）")
        private Long targetId;

        @Schema(description = "目标名称（小区名/楼栋名，可空）")
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
