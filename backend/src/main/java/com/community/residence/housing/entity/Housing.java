package com.community.residence.housing.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 房源信息（housing 表；游客端展示主体，浏览计数直接累加） */
@Data
@TableName("housing")
@Schema(description = "房源信息")
public class Housing {

    @TableId(type = IdType.AUTO)
    @Schema(description = "房源ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "关联房屋ID")
    private Long houseId;

    @Schema(description = "房源标题")
    private String title;

    @Schema(description = "房源描述")
    private String description;

    @Schema(description = "月租金")
    private BigDecimal monthlyRent;

    @Schema(description = "押金")
    private BigDecimal deposit;

    @Schema(description = "租售类型：RENT-出租, SALE-出售（R53 v1.2）")
    private String rentType;

    @Schema(description = "户型（冗余自 house.layout，列表筛选用，R53 v1.2）")
    private String layout;

    @Schema(description = "房源图片（逗号分隔URL）")
    private String images;

    @Schema(description = "状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线")
    private String status;

    @Schema(description = "浏览次数")
    private Integer viewCount;

    @Schema(description = "发布时间")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
