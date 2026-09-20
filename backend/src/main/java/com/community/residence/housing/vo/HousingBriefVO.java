package com.community.residence.housing.vo;

import com.community.residence.housing.entity.Housing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 房源摘要（房屋管理分页列表内嵌，接口设计.md 9.15.3）。
 * 与 HousingSummaryVO（R62 一体化树，无封面图）字段语义一致，额外携带 coverImage，
 * 独立成类避免改动既有 VO 语义。
 */
@Data
@Schema(description = "房源摘要（房屋管理列表内嵌）")
public class HousingBriefVO {

    @Schema(description = "房源ID")
    private Long id;

    @Schema(description = "房源标题")
    private String title;

    @Schema(description = "状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线")
    private String status;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金（null=面议）")
    private BigDecimal deposit;

    @Schema(description = "租售类型：RENT-出租, SALE-出售")
    private String rentType;

    @Schema(description = "封面图（housing.images 逗号串首个非空项，/uploads/ 相对路径原样返回；无图为 null）")
    private String coverImage;

    public static HousingBriefVO from(Housing entity) {
        HousingBriefVO vo = new HousingBriefVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setStatus(entity.getStatus());
        vo.setMonthlyRent(entity.getMonthlyRent());
        vo.setDeposit(entity.getDeposit());
        vo.setRentType(entity.getRentType());
        vo.setCoverImage(firstImage(entity.getImages()));
        return vo;
    }

    /** images 为逗号分隔 URL 串（如 ", /uploads/a.png,/uploads/b.png"），取首个非空项作封面 */
    private static String firstImage(String images) {
        if (!StringUtils.hasText(images)) {
            return null;
        }
        for (String part : images.split(",")) {
            if (StringUtils.hasText(part)) {
                return part.trim();
            }
        }
        return null;
    }
}
