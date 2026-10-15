package com.community.residence.community.entity;
import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("house_image")
@Schema(description="房屋相册图片")
public class HouseImage {
    @TableId(type=IdType.AUTO) @Schema(description="图片ID") private Long id;
    @Schema(description="房屋ID") private Long houseId;
    @Schema(description="服务器上传存储引用") private String storageKey;
    @Schema(description="PHOTO实拍/FLOOR_PLAN平面图") private String kind;
    @Schema(description="图片说明") private String caption;
    @Schema(description="排列序号") private Integer sortOrder;
    @Schema(description="上传管理员ID") private Long uploadedBy;
    @Schema(description="上传时间") private LocalDateTime createdAt;
}
