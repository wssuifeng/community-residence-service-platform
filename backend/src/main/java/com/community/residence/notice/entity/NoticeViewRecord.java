package com.community.residence.notice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告查看记录（notice_view_record 表，同一用户同一公告仅一条，唯一约束防重） */
@Data
@TableName("notice_view_record")
@Schema(description = "公告查看记录")
public class NoticeViewRecord {

    @TableId(type = IdType.AUTO)
    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "公告ID")
    private Long noticeId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "查看时间")
    private LocalDateTime viewTime;
}
