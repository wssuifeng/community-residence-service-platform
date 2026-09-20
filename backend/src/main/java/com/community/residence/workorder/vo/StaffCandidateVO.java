package com.community.residence.workorder.vo;

import com.community.residence.auth.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 可绑定服务人员候选（能力绑定的人员选择池；不做社区过滤，仅功能级权限） */
@Data
@Schema(description = "可绑定服务人员候选")
public class StaffCandidateVO {

    @Schema(description = "服务人员ID")
    private Long id;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "用户名")
    private String username;

    public static StaffCandidateVO from(SysUser entity) {
        StaffCandidateVO vo = new StaffCandidateVO();
        vo.setId(entity.getId());
        vo.setRealName(entity.getRealName());
        vo.setUsername(entity.getUsername());
        return vo;
    }
}
