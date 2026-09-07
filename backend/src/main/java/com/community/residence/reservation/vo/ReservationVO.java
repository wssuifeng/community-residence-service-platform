package com.community.residence.reservation.vo;

import com.community.residence.reservation.entity.ResourceReservation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 资源预约响应 */
@Data
@Schema(description = "资源预约")
public class ReservationVO {

    @Schema(description = "预约ID")
    private Long id;

    @Schema(description = "预约人ID")
    private Long userId;

    @Schema(description = "预约人姓名")
    private String userName;

    @Schema(description = "资源ID")
    private Long resourceId;

    @Schema(description = "资源名称")
    private String resourceName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "预约日期")
    private LocalDate reserveDate;

    @Schema(description = "开始时间")
    private LocalTime startTime;

    @Schema(description = "结束时间")
    private LocalTime endTime;

    @Schema(description = "状态：PENDING-待审核, RESERVED-已预约, COMPLETED-已完成, REJECTED-已拒绝, CANCELLED-已取消, VIOLATED-已违约")
    private String status;

    @Schema(description = "预约用途")
    private String purpose;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "预约时间")
    private LocalDateTime createdAt;

    public static ReservationVO from(ResourceReservation entity) {
        ReservationVO vo = new ReservationVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setResourceId(entity.getResourceId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setReserveDate(entity.getReserveDate());
        vo.setStartTime(entity.getStartTime());
        vo.setEndTime(entity.getEndTime());
        vo.setStatus(entity.getStatus());
        vo.setPurpose(entity.getPurpose());
        vo.setContactPhone(entity.getContactPhone());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
