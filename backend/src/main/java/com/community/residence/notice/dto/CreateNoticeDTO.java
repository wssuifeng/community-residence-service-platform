package com.community.residence.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告创建/更新请求（更新接口复用；communityId 为空表示全系统广播，仅超管） */
@Data
@Schema(description = "公告创建/更新请求")
public class CreateNoticeDTO {

    @Schema(description = "目标社区ID（全系统广播时为空，仅超级管理员）")
    private Long communityId;

    @Schema(description = "公告标题")
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 200, message = "公告标题最多 200 字符")
    private String title;

    @Schema(description = "公告内容")
    @NotBlank(message = "公告内容不能为空")
    @Size(max = 5000, message = "公告内容最多 5000 字符")
    private String content;

    @Schema(description = "发布时间（定时发布）")
    @NotNull(message = "发布时间不能为空")
    private LocalDateTime publishTime;

    @Schema(description = "失效时间（为空时默认发布时间 + 30 天）")
    private LocalDateTime endTime;
}
