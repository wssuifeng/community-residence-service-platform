package com.community.residence.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 管理员-社区绑定（sys_admin_community：ADMIN 数据级权限的权威来源） */
@Data
@TableName("sys_admin_community")
@Schema(description = "管理员-社区绑定")
public class SysAdminCommunity {

    @TableId(type = IdType.AUTO)
    @Schema(description = "绑定ID")
    private Long id;

    @Schema(description = "管理员ID")
    private Long adminId;

    @Schema(description = "社区ID")
    private Long communityId;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;
}
