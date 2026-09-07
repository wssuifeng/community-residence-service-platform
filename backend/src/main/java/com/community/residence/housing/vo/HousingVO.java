package com.community.residence.housing.vo;

import com.community.residence.housing.entity.Housing;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 房源信息响应 */
@Data
@Schema(description = "房源信息")
public class HousingVO {

    @Schema(description = "房源ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "所属社区名称")
    private String communityName;

    @Schema(description = "关联房屋ID")
    private Long houseId;

    @Schema(description = "房屋位置（楼栋-单元-房号）")
    private String houseLocation;

    @Schema(description = "房源标题")
    private String title;

    @Schema(description = "房源描述")
    private String description;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    private BigDecimal deposit;

    @Schema(description = "房源图片URL列表")
    private String[] images;

    @Schema(description = "状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线")
    private String status;

    @Schema(description = "浏览次数")
    private Integer viewCount;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static HousingVO from(Housing entity) {
        HousingVO vo = new HousingVO();
        vo.setId(entity.getId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseId(entity.getHouseId());
        vo.setTitle(entity.getTitle());
        vo.setDescription(entity.getDescription());
        vo.setMonthlyRent(entity.getMonthlyRent());
        vo.setDeposit(entity.getDeposit());
        vo.setImages(entity.getImages() != null && !entity.getImages().isBlank()
                ? entity.getImages().split(",") : new String[0]);
        vo.setStatus(entity.getStatus());
        vo.setViewCount(entity.getViewCount());
        vo.setPublishTime(entity.getPublishTime());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
