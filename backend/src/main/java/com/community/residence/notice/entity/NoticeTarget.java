package com.community.residence.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告目标范围（notice_target 表：COMMUNITY-按社区, BUILDING-按楼栋定向, GUEST-游客可见） */
@Data
@TableName("notice_target")
@Schema(description = "公告目标范围")
public class NoticeTarget {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "公告ID")
    private Long noticeId;

    @Schema(description = "目标类型：COMMUNITY-社区, BUILDING-楼栋, GUEST-游客可见（DEF-046）")
    private String targetType;

    @Schema(description = "目标ID（GUEST 行固定 0）")
    private Long targetId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
