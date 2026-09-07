package com.community.residence.auth.vo;

import com.community.residence.auth.entity.SysOperationLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志响应 */
@Data
@Schema(description = "操作日志")
public class OperationLogVO {

    @Schema(description = "日志ID")
    private Long id;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人类型：RESIDENT-居民, ADMIN-管理员, STAFF-服务人员")
    private String operatorType;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "操作类型")
    private String operationType;

    @Schema(description = "操作对象类型")
    private String targetType;

    @Schema(description = "操作对象ID")
    private Long targetId;

    @Schema(description = "操作内容（JSON）")
    private String content;

    @Schema(description = "操作IP")
    private String ip;

    @Schema(description = "User-Agent")
    private String userAgent;

    @Schema(description = "操作时间")
    private LocalDateTime createdAt;

    public static OperationLogVO from(SysOperationLog entity) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(entity.getId());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorType(entity.getOperatorType());
        vo.setCommunityId(entity.getCommunityId());
        vo.setOperationType(entity.getOperationType());
        vo.setTargetType(entity.getTargetType());
        vo.setTargetId(entity.getTargetId());
        vo.setContent(entity.getContent());
        vo.setIp(entity.getIp());
        vo.setUserAgent(entity.getUserAgent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
