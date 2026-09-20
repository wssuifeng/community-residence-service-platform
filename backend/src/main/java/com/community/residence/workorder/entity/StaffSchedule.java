package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 服务人员排班（staff_schedule 表；一人一社区一天一条，shift_type 见 ShiftType，
    start_time/end_time 为班次时间快照，REST 休息为空） */
@Data
@TableName("staff_schedule")
@Schema(description = "服务人员排班")
public class StaffSchedule {

    @TableId(type = IdType.AUTO)
    @Schema(description = "排班ID")
    private Long id;

    @Schema(description = "服务人员用户ID")
    private Long staffId;

    @Schema(description = "排班社区ID")
    private Long communityId;

    @Schema(description = "排班日期")
    private LocalDate workDate;

    @Schema(description = "班次：MORNING-早班, AFTERNOON-午班, EVENING-晚班, FULL-全天, REST-休息")
    private String shiftType;

    @Schema(description = "班次开始时间（REST 为空）")
    private LocalTime startTime;

    @Schema(description = "班次结束时间（REST 为空）")
    private LocalTime endTime;

    @Schema(description = "备注（代班/调休说明）")
    private String remark;

    @Schema(description = "排班人用户ID")
    private Long createdBy;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
