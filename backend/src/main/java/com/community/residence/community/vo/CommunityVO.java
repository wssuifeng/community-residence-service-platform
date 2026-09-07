package com.community.residence.community.vo;

import com.community.residence.community.entity.Community;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 社区信息响应 */
@Data
@Schema(description = "社区信息")
public class CommunityVO {

    @Schema(description = "社区ID")
    private Long id;

    @Schema(description = "社区名称")
    private String name;

    @Schema(description = "详细地址")
    private String address;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "联系人")
    private String contactPerson;

    @Schema(description = "社区简介")
    private String description;

    @Schema(description = "状态：ACTIVE-运营中, INACTIVE-已停用")
    private String status;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public static CommunityVO from(Community entity) {
        CommunityVO vo = new CommunityVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setAddress(entity.getAddress());
        vo.setContactPhone(entity.getContactPhone());
        vo.setContactPerson(entity.getContactPerson());
        vo.setDescription(entity.getDescription());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
