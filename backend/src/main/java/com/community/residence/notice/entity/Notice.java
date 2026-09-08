package com.community.residence.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告（notice 表；社区归属经 notice_target 关联，全系统广播无 target 记录） */
@Data
@TableName("notice")
@Schema(description = "公告")
public class Notice {

    @TableId(type = IdType.AUTO)
    @Schema(description = "公告ID")
    private Long id;

    @Schema(description = "公告标题")
    private String title;

    @Schema(description = "公告内容")
    private String content;

    @Schema(description = "发布人ID")
    private Long publisherId;

    @Schema(description = "状态：DRAFT-草稿, PUBLISHED-已发布, WITHDRAWN-已撤回")
    private String status;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "生效时间")
    private LocalDateTime startTime;

    @Schema(description = "失效时间")
    private LocalDateTime endTime;

    @Schema(description = "浏览次数")
    private Integer viewCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
