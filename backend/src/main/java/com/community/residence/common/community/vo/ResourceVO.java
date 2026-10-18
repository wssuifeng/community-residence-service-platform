package com.community.residence.common.community.vo;

import com.community.residence.common.community.entity.PublicResource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;

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

    @Schema(description = "每时段可承载人数上限（Slot Grid：资源级·每时段人数）")
    private Integer capacity;

    @Schema(description = "预约最小单位（分钟）：15/30/60，默认 30（Slot Grid 栅格粒度）")
    private Integer slotUnit;

    @Schema(description = "资源描述")
    private String description;

    @Schema(description = "展示图片地址列表（无图返回空数组，与 housing.images 同口径）")
    private String[] images;

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
        vo.setSlotUnit(entity.getSlotUnit());
        vo.setDescription(entity.getDescription());
        vo.setImages(splitImages(entity.getImages()));
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /* 逗号分隔存储 → 图片数组（与 housing.images 同口径）：null / 空白 / 空项一律归一为
       空数组（不返回 null），并顺带清理项首尾空白，避免前端拿到空串渲染坏图 */
    private static String[] splitImages(String images) {
        if (!StringUtils.hasText(images)) {
            return new String[0];
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }
}
