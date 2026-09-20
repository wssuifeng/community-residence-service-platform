package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 服务人员擅长服务类别（staff_service_category 表，多对多；V19 派单候选第二档依据） */
@Data
@TableName("staff_service_category")
@Schema(description = "服务人员擅长服务类别")
public class StaffServiceCategory {

    @TableId(type = IdType.AUTO)
    @Schema(description = "绑定ID")
    private Long id;

    @Schema(description = "服务人员用户ID")
    private Long staffId;

    @Schema(description = "服务类别ID")
    private Long categoryId;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;
}
