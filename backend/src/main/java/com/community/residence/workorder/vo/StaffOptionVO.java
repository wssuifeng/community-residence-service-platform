package com.community.residence.workorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 可派单服务人员选项（DEF-025：派单对话框下拉，最小暴露面——无用户名/手机号；
    V19 起附调度推荐档位、社区/类别匹配、今日班次与在手工单数） */
@Data
@Schema(description = "可派单服务人员选项")
public class StaffOptionVO {

    @Schema(description = "服务人员ID")
    private Long id;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "推荐档位（越小越推荐）：1-常驻本社区且擅长该类别, 2-常驻本社区, 3-擅长该类别, 4-其他")
    private Integer recommendLevel;

    @Schema(description = "是否常驻当前社区（staff_community 绑定）")
    private Boolean matchedCommunity;

    @Schema(description = "是否擅长当前工单类别（staff_service_category 绑定）")
    private Boolean matchedCategory;

    @Schema(description = "常驻社区名称（逗号拼接）")
    private String communityNames;

    @Schema(description = "今日班次标签：早班/午班/晚班/全天/休息；空=当日未排班")
    private String todayShiftLabel;

    @Schema(description = "当前未完结工单数（不含已完成/已关闭/已驳回/已取消）")
    private Integer activeOrderCount;

    public static StaffOptionVO of(Long id, String realName) {
        StaffOptionVO vo = new StaffOptionVO();
        vo.setId(id);
        vo.setRealName(realName);
        return vo;
    }
}
