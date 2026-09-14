package com.community.residence.workorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 可派单服务人员选项（DEF-025：派单对话框下拉，最小暴露面——无用户名/手机号） */
@Data
@Schema(description = "可派单服务人员选项")
public class StaffOptionVO {

    @Schema(description = "服务人员ID")
    private Long id;

    @Schema(description = "真实姓名")
    private String realName;

    public static StaffOptionVO of(Long id, String realName) {
        StaffOptionVO vo = new StaffOptionVO();
        vo.setId(id);
        vo.setRealName(realName);
        return vo;
    }
}
