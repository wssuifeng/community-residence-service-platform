package com.community.residence.lease.vo;

import com.community.residence.lease.entity.LeaseRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 租住记录响应（附到期标注，非状态值） */
@Data
@Schema(description = "租住记录")
public class LeaseVO {

    @Schema(description = "租住记录ID")
    private Long id;

    @Schema(description = "租客ID")
    private Long tenantId;

    @Schema(description = "租客姓名")
    private String tenantName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "房屋位置（楼栋-单元-房号）")
    private String houseLocation;

    @Schema(description = "租期开始日期")
    private LocalDate startDate;

    @Schema(description = "租期结束日期")
    private LocalDate endDate;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    private BigDecimal deposit;

    @Schema(description = "状态：PENDING-待审核, ACTIVE-租住中, MOVED_OUT-已迁出, ARCHIVED-已归档, REJECTED-已拒绝")
    private String status;

    @Schema(description = "到期标注：EXPIRING-即将到期(30天内), EXPIRED-已到期；其余为空（日期自动判定，非状态值）")
    private String expiryFlag;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static LeaseVO from(LeaseRecord entity) {
        LeaseVO vo = new LeaseVO();
        vo.setId(entity.getId());
        vo.setTenantId(entity.getTenantId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseId(entity.getHouseId());
        vo.setStartDate(entity.getStartDate());
        vo.setEndDate(entity.getEndDate());
        vo.setMonthlyRent(entity.getMonthlyRent());
        vo.setDeposit(entity.getDeposit());
        vo.setStatus(entity.getStatus());
        vo.setRemark(entity.getRemark());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
