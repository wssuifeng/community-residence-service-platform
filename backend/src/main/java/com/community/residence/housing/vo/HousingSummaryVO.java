package com.community.residence.housing.vo;

import com.community.residence.housing.entity.Housing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 房源摘要（R62 一体化树房屋节点内嵌：未挂牌为 null） */
@Data
@Schema(description = "房源摘要")
public class HousingSummaryVO {

    @Schema(description = "房源ID")
    private Long id;

    @Schema(description = "状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线")
    private String status;

    @Schema(description = "房源标题")
    private String title;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金（null=面议）")
    private BigDecimal deposit;

    @Schema(description = "租售类型：RENT-出租, SALE-出售")
    private String rentType;

    public static HousingSummaryVO from(Housing entity) {
        HousingSummaryVO vo = new HousingSummaryVO();
        vo.setId(entity.getId());
        vo.setStatus(entity.getStatus());
        vo.setTitle(entity.getTitle());
        vo.setMonthlyRent(entity.getMonthlyRent());
        vo.setDeposit(entity.getDeposit());
        vo.setRentType(entity.getRentType());
        return vo;
    }
}
