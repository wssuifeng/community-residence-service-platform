package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 管理端批量占用回显，不逐挂牌查业务链。 */
@Data
public class HousingOccupancyVO {
    @Schema(description = "挂牌ID") private Long id;
    @Schema(description = "占用：FREE/APPLYING/TO_SIGN/WAITING_FIRST_PAYMENT/OCCUPIED") private String occupancy;
    @Schema(description = "唯一当前申请ID，历史重复链时为空") private Long applicationId;
    @Schema(description = "唯一当前租约ID，历史重复链时为空") private Long leaseId;
}
