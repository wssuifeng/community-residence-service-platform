package com.community.residence.common.community.vo;

import com.community.residence.common.community.entity.Unit;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 单元信息响应 */
@Data
@Schema(description = "单元信息")
public class UnitVO {

    @Schema(description = "单元ID")
    private Long id;

    @Schema(description = "所属楼栋ID")
    private Long buildingId;

    @Schema(description = "所属楼栋名称")
    private String buildingName;

    @Schema(description = "所属小区ID")
    private Long communityId;

    @Schema(description = "单元名称")
    private String name;

    @Schema(description = "本级业务编号，与内部主键分离")
    private String businessCode;

    @Schema(description = "单元描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static UnitVO from(Unit entity) {
        UnitVO vo = new UnitVO();
        vo.setId(entity.getId());
        vo.setBuildingId(entity.getBuildingId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setName(entity.getName());
        vo.setBusinessCode(entity.getBusinessCode());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
