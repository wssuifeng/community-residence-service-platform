package com.community.residence.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 公告创建/更新请求（更新接口复用；目标为空表示全系统广播，仅超管）。
 *  R25 v1.2：targets 支持多社区/楼栋定向；communityId 单目标写法保留向后兼容
 *  （两者并设时以 targets 为准，communityId 忽略）。 */
@Data
@Schema(description = "公告创建/更新请求")
public class CreateNoticeDTO {

    @Schema(description = "目标社区ID（单目标旧写法，向后兼容；新调用请用 targets）")
    private Long communityId;

    @Schema(description = "目标范围列表（R25 v1.2：多社区/楼栋定向；空=全系统广播仅超管）")
    private List<TargetItemDTO> targets;

    @Schema(description = "置顶：0-普通, 1-置顶（R25 v1.2）")
    private Integer isPinned;

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

    /** 目标范围项 */
    @Data
    @Schema(description = "公告目标范围项")
    public static class TargetItemDTO {

        @Schema(description = "目标类型：COMMUNITY-社区, BUILDING-楼栋")
        @NotBlank(message = "目标类型不能为空")
        private String targetType;

        @Schema(description = "目标ID")
        @NotNull(message = "目标ID不能为空")
        private Long targetId;
    }
}
