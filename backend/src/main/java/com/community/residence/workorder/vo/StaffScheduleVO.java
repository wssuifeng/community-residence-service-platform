package com.community.residence.workorder.vo;

import com.community.residence.common.constant.ShiftType;
import com.community.residence.workorder.entity.StaffSchedule;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 服务人员排班响应 */
@Data
@Schema(description = "服务人员排班")
public class StaffScheduleVO {

    @Schema(description = "排班ID")
    private Long id;

    @Schema(description = "服务人员ID")
    private Long staffId;

    @Schema(description = "服务人员姓名")
    private String staffName;

    @Schema(description = "排班社区ID")
    private Long communityId;

    @Schema(description = "排班社区名称")
    private String communityName;

    @Schema(description = "排班日期")
    private LocalDate workDate;

    @Schema(description = "班次：MORNING/AFTERNOON/EVENING/FULL/REST")
    private String shiftType;

    @Schema(description = "班次标签：早班/午班/晚班/全天/休息")
    private String shiftLabel;

    @Schema(description = "班次开始时间（REST 为空）")
    private LocalTime startTime;

    @Schema(description = "班次结束时间（REST 为空）")
    private LocalTime endTime;

    @Schema(description = "备注")
    private String remark;

    public static StaffScheduleVO from(StaffSchedule entity) {
        StaffScheduleVO vo = new StaffScheduleVO();
        vo.setId(entity.getId());
        vo.setStaffId(entity.getStaffId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setWorkDate(entity.getWorkDate());
        vo.setShiftType(entity.getShiftType());
        vo.setShiftLabel(ShiftType.label(entity.getShiftType()));
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setRemark(entity.getRemark());
        return vo;
    }
}
