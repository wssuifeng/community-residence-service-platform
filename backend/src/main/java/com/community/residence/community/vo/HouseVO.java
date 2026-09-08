package com.community.residence.community.vo;

import com.community.residence.community.entity.House;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 房屋信息响应 */
@Data
@Schema(description = "房屋信息")
public class HouseVO {

    @Schema(description = "房屋ID")
    private Long id;

    @Schema(description = "所属单元ID")
    private Long unitId;

    @Schema(description = "所属单元名称")
    private String unitName;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房号")
    private String houseNumber;

    @Schema(description = "楼层")
    private Integer floor;

    @Schema(description = "建筑面积（平方米）")
    private BigDecimal area;

    @Schema(description = "房间数")
    private Integer roomCount;

    @Schema(description = "户型")
    private String layout;

    @Schema(description = "朝向")
    private String orientation;

    @Schema(description = "状态：VACANT-空置, OCCUPIED-已入住, RESERVED-预留, MAINTENANCE-维护中")
    private String status;

    @Schema(description = "房屋描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static HouseVO from(House entity) {
        HouseVO vo = new HouseVO();
        vo.setId(entity.getId());
        vo.setUnitId(entity.getUnitId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseNumber(entity.getHouseNumber());
        vo.setFloor(entity.getFloor());
        vo.setArea(entity.getArea());
        vo.setRoomCount(entity.getRoomCount());
        vo.setLayout(entity.getLayout());
        vo.setOrientation(entity.getOrientation());
        vo.setStatus(entity.getStatus());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
