package com.community.residence.workorder.vo;

import com.community.residence.workorder.entity.ServiceCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 服务类别响应（树形；children 为二级类别） */
@Data
@Schema(description = "服务类别")
public class CategoryVO {

    @Schema(description = "类别ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "类别名称")
    private String name;

    @Schema(description = "类别描述")
    private String description;

    @Schema(description = "父类别ID")
    private Long parentId;

    @Schema(description = "排序")
    private Integer sortOrder;

    @Schema(description = "是否启用")
    private Integer isActive;

    @Schema(description = "二级类别")
    private List<CategoryVO> children;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static CategoryVO from(ServiceCategory entity) {
        CategoryVO vo = new CategoryVO();
        vo.setId(entity.getId());
        vo.setCommunityId(entity.getCommunityId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setParentId(entity.getParentId());
        vo.setSortOrder(entity.getSortOrder());
        vo.setIsActive(entity.getIsActive());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
