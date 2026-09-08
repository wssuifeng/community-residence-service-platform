package com.community.residence.notice.vo;

import com.community.residence.notice.entity.Notice;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告信息响应 */
@Data
@Schema(description = "公告信息")
public class NoticeVO {

    @Schema(description = "公告ID")
    private Long id;

    @Schema(description = "目标社区ID（全系统广播时为空）")
    private Long communityId;

    @Schema(description = "目标社区名称")
    private String communityName;

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
        vo.setPublisherId(entity.getPublisherId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
