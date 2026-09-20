package com.community.residence.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 服务人员常驻社区绑定（staff_community 表，多对多；V19 派单候选第一档依据） */
@Data
@TableName("staff_community")
@Schema(description = "服务人员常驻社区绑定")
public class StaffCommunity {

    @TableId(type = IdType.AUTO)
    @Schema(description = "绑定ID")
    private Long id;

    @Schema(description = "服务人员用户ID（sys_user.role=STAFF）")
    private Long staffId;

    @Schema(description = "常驻社区ID")
    private Long communityId;

    @Schema(description = "绑定时间")
    private LocalDateTime createdAt;
}
