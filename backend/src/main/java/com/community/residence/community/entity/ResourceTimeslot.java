package com.community.residence.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** 资源可预约时段（resource_timeslot 表，按周循环的时段模板） */
@Data
@TableName("resource_timeslot")
@Schema(description = "资源可预约时段")
public class ResourceTimeslot {

    @TableId(type = IdType.AUTO)
    @Schema(description = "时段ID")
    private Long id;

    @Schema(description = "资源ID")
    private Long resourceId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "星期几：1-周一, 7-周日")
    private Integer dayOfWeek;

    @Schema(description = "开始时间")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    private LocalTime endTime;

    @Schema(description = "是否可预约：0-不可预约, 1-可预约")
    private Integer isAvailable;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
