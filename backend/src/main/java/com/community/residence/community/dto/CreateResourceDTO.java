package com.community.residence.community.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公共资源创建/更新请求（更新接口复用本 DTO） */
@Data
@Schema(description = "公共资源创建/更新请求")
public class CreateResourceDTO {

    @Schema(description = "所属社区ID")
    @NotNull(message = "所属社区不能为空")
    private Long communityId;

    @Schema(description = "资源名称")
    @NotBlank(message = "资源名称不能为空")
    @Size(max = 100, message = "资源名称最多 100 字符")
    private String name;

    @Schema(description = "资源类型：MEETING_ROOM-会议室, GYM-健身房, PARKING-停车位")
    @NotBlank(message = "资源类型不能为空")
    @Size(max = 50, message = "资源类型最多 50 字符")
    private String type;

    @Schema(description = "位置描述")
    @Size(max = 200, message = "位置描述最多 200 字符")
    private String location;

    @Schema(description = "每时段可承载人数上限（Slot Grid：资源级·每时段人数）")
    @Min(value = 1, message = "每时段可承载人数最少为 1")
    private Integer capacity;

    @Schema(description = "预约最小单位（分钟）：15/30/60，默认 30（Slot Grid 栅格粒度）")
    @Min(value = 15, message = "预约最小单位取值 15/30/60")
    @Max(value = 60, message = "预约最小单位取值 15/30/60")
    private Integer slotUnit;

    @Schema(description = "资源描述")
    @Size(max = 1000, message = "资源描述最多 1000 字符")
    private String description;
}
