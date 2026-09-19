package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 带看人候选选项（R59：分配弹窗下拉，最小暴露面——无用户名/手机号；role 供界面区分身份） */
@Data
@Schema(description = "带看人候选选项")
public class AssigneeOptionVO {

    @Schema(description = "账号ID")
    private Long id;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "角色（STAFF 服务人员 / ADMIN 社区管理员）")
    private String role;

    public static AssigneeOptionVO of(Long id, String realName, String role) {
        AssigneeOptionVO vo = new AssigneeOptionVO();
        vo.setId(id);
        vo.setRealName(realName);
        vo.setRole(role);
        return vo;
    }
}
