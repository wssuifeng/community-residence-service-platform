package com.community.residence.community.vo;

import com.community.residence.community.entity.PublicResource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 公共资源信息响应 */
@Data
@Schema(description = "公共资源信息")
public class ResourceVO {

    @Schema(description = "资源ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "所属社区名称")
    private String communityName;

    @Schema(description = "资源名称")
    private String name;

    @Schema(description = "资源类型：MEETING_ROOM-会议室, GYM-健身房, PARKING-停车位")
    private String type;

    @Schema(description = "位置描述")
    private String location;

    @Schema(description = "容纳人数/车位数")
    private Integer capacity;

    @Schema(description = "资源描述")
    private String description;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static ResourceVO from(PublicResource entity) {
        ResourceVO vo = new ResourceVO();
        vo.setId(entity.getId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setName(entity.getName());
        vo.setType(entity.getType());
        vo.setLocation(entity.getLocation());
        vo.setCapacity(entity.getCapacity());
        vo.setDescription(entity.getDescription());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
