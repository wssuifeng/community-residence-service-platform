package com.community.residence.housing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 看房预约（viewing_appointment 表；V5 起支持游客预约：user_id 可空 + visitor 信息） */
@Data
@TableName("viewing_appointment")
@Schema(description = "看房预约")
public class ViewingAppointment {

    @TableId(type = IdType.AUTO)
    @Schema(description = "预约ID")
    private Long id;

    @Schema(description = "预约人ID（游客预约为空）")
    private Long userId;

    @Schema(description = "游客姓名（游客预约时填写）")
    private String visitorName;

    @Schema(description = "游客手机号（游客预约时填写）")
    private String visitorPhone;

    @Schema(description = "房源ID")
    private Long housingId;

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

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
