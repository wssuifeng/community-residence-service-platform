package com.community.residence.community.vo;

import com.community.residence.community.entity.HouseStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 房屋状态变更历史响应 */
@Data
@Schema(description = "房屋状态变更历史")
public class HouseStatusHistoryVO {

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "房屋ID")
    private Long houseId;

    @Schema(description = "变更前状态")
    private String oldStatus;

    @Schema(description = "变更后状态")
    private String newStatus;

    @Schema(description = "变更备注")
    private String remark;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "变更时间")
    private LocalDateTime createdAt;

    public static HouseStatusHistoryVO from(HouseStatusHistory entity) {
        HouseStatusHistoryVO vo = new HouseStatusHistoryVO();
        vo.setId(entity.getId());
        vo.setHouseId(entity.getHouseId());
        vo.setOldStatus(entity.getOldStatus());
        vo.setNewStatus(entity.getNewStatus());
        vo.setRemark(entity.getRemark());
        vo.setOperatorId(entity.getOperatorId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
