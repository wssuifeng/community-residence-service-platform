package com.community.residence.housing.vo;

import com.community.residence.housing.entity.ViewingAppointment;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 看房预约响应 */
@Data
@Schema(description = "看房预约")
public class ViewingAppointmentVO {

    @Schema(description = "预约ID")
    private Long id;

    @Schema(description = "预约人ID（游客预约为空）")
    private Long userId;

    @Schema(description = "预约人姓名（居民取实名，游客取填写的姓名）")
    private String visitorName;

    @Schema(description = "房源ID")
    private Long housingId;

    @Schema(description = "房源标题")
    private String housingTitle;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "预约日期")
    private LocalDate appointmentDate;

    @Schema(description = "开始时间")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    private LocalTime endTime;

    @Schema(description = "状态：TO_CONFIRM-待确认, RESERVED-已预约, COMPLETED-已完成, CANCELLED-已取消, VIOLATED-已违约")
    private String status;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "预约时间")
    private LocalDateTime createdAt;

    public static ViewingAppointmentVO from(ViewingAppointment entity) {
        ViewingAppointmentVO vo = new ViewingAppointmentVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setVisitorName(entity.getVisitorName());
        vo.setHousingId(entity.getHousingId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setAppointmentDate(entity.getAppointmentDate());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setStatus(entity.getStatus());
        vo.setContactPhone(entity.getContactPhone());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
