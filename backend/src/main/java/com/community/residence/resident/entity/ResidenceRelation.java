package com.community.residence.resident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 居住关系（residence_relation 表，M:N 居民-房屋关联，move_out_date 为空表示在住） */
@Data
@TableName("residence_relation")
@Schema(description = "居住关系")
public class ResidenceRelation {

    @TableId(type = IdType.AUTO)
    @Schema(description = "关系ID")
    private Long id;

    @Schema(description = "居民ID")
    private Long residentId;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    private String relationType;

    @Schema(description = "入住日期")
    private LocalDate moveInDate;

    @Schema(description = "迁出日期（为空表示在住）")
    private LocalDate moveOutDate;

    @Schema(description = "是否主要居住地：0-否, 1-是")
    private Integer isPrimary;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
