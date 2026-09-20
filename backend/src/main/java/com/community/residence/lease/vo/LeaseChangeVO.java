package com.community.residence.lease.vo;

import com.community.residence.lease.entity.LeaseChangeLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 租约变更历史响应 */
@Data
@Schema(description = "租约变更历史")
public class LeaseChangeVO {

    @Schema(description = "变更记录ID")
    private Long id;

    @Schema(description = "变更类型：CREATE-登记, ATTRIBUTE-属性变更, RENEW-续租, STATUS-状态流转, AGREEMENT-协议签约")
    private String changeType;

    @Schema(description = "字段名")
    private String fieldName;

    @Schema(description = "字段中文名")
    private String fieldLabel;

    @Schema(description = "变更前值")
    private String oldValue;

    @Schema(description = "变更后值")
    private String newValue;

    @Schema(description = "变更原因/备注")
    private String reason;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人姓名")
    private String operatorName;

    @Schema(description = "操作人身份：ADMIN-管理方, RESIDENT-居民, SYSTEM-系统任务")
    private String operatorType;

    @Schema(description = "变更时间")
    private LocalDateTime createdAt;

    public static LeaseChangeVO from(LeaseChangeLog entity) {
        LeaseChangeVO vo = new LeaseChangeVO();
        vo.setId(entity.getId());
        vo.setChangeType(entity.getChangeType());
        vo.setFieldName(entity.getFieldName());
        vo.setFieldLabel(entity.getFieldLabel());
        vo.setOldValue(entity.getOldValue());
        vo.setNewValue(entity.getNewValue());
        vo.setReason(entity.getReason());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperatorType(entity.getOperatorType());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
