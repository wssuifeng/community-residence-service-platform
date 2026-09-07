package com.community.residence.resident.vo;

import com.community.residence.resident.entity.ResidenceRelation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 居住关系响应 */
@Data
@Schema(description = "居住关系")
public class RelationVO {

    @Schema(description = "关系ID")
    private Long id;

    @Schema(description = "居民ID")
    private Long residentId;

    @Schema(description = "居民姓名")
    private String residentName;

    @Schema(description = "居民手机号")
    private String residentPhone;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "房屋位置（楼栋-单元-房号）")
    private String houseLocation;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    private String relationType;

    @Schema(description = "入住日期")
    private LocalDate moveInDate;

    @Schema(description = "迁出日期（为空表示在住）")
    private LocalDate moveOutDate;

    @Schema(description = "是否在住（move_out_date 为空即 ACTIVE）")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static RelationVO from(ResidenceRelation entity) {
        RelationVO vo = new RelationVO();
        vo.setId(entity.getId());
        vo.setResidentId(entity.getResidentId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setHouseId(entity.getHouseId());
        vo.setRelationType(entity.getRelationType());
        vo.setMoveInDate(entity.getMoveInDate());
        vo.setMoveOutDate(entity.getMoveOutDate());
        vo.setStatus(entity.getMoveOutDate() == null ? "ACTIVE" : "MOVED_OUT");
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
