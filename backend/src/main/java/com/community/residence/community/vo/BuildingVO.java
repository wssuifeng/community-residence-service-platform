package com.community.residence.community.vo;

import com.community.residence.community.entity.Building;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 楼栋信息响应 */
@Data
@Schema(description = "楼栋信息")
public class BuildingVO {

    @Schema(description = "楼栋ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "所属社区名称")
    private String communityName;

    @Schema(description = "楼栋名称")
    private String name;

    @Schema(description = "楼层数")
    private Integer floors;

    @Schema(description = "楼栋描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static BuildingVO from(Building entity) {
        BuildingVO vo = new BuildingVO();
        vo.setId(entity.getId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setName(entity.getName());
        vo.setFloors(entity.getFloors());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
