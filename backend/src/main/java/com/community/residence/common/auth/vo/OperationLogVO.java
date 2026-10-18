package com.community.residence.common.auth.vo;

import com.community.residence.common.auth.entity.SysOperationLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** 操作日志响应 */
@Data
@Schema(description = "操作日志")
public class OperationLogVO {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<String>> MODULE_TARGETS = Map.ofEntries(
            Map.entry("COMMUNITY", List.of("COMMUNITY", "BUILDING", "UNIT", "HOUSE", "PUBLIC_RESOURCE", "RESOURCE_TIMESLOT")),
            Map.entry("RESIDENT", List.of("RESIDENT", "RESIDENCE_RELATION", "RESIDENCE_APPLICATION")),
            Map.entry("LEASE", List.of("LEASE", "LEASE_RECORD", "LEASE_AGREEMENT", "AGREEMENT_TEMPLATE", "LEASE_MOVE_OUT", "LEASE_DEPOSIT_REFUND", "LEASE_PAYMENT")),
            Map.entry("HOUSING", List.of("HOUSING", "VIEWING_APPOINTMENT", "HOUSING_TIMESLOT")),
            Map.entry("WORK_ORDER", List.of("WORK_ORDER", "SERVICE_CATEGORY", "STAFF_SCHEDULE", "STAFF_CAPABILITY")),
            Map.entry("RESERVATION", List.of("RESERVATION", "RESOURCE_RESERVATION", "VIOLATION_RECORD")));

    @Schema(description = "操作人显示名称，已删除账号以身份和ID回落")
    private String operatorName;
    @Schema(description = "模块标识（由操作对象类型映射）")
    private String module;
    @Schema(description = "操作类型（operationType兼容别名）")
    private String action;
    @Schema(description = "操作描述（由已保存content解析，不伪造原始请求）")
    private String description;
    @Schema(description = "操作IP（ip兼容别名）")
    private String ipAddress;
    @Schema(description = "安全白名单请求参数；历史未采集时为空，不包含原始请求体")
    private String requestParams;

    public static List<String> targetsForModule(String module) {
        return MODULE_TARGETS.getOrDefault(module, List.of(module));
    }

    public static String moduleForTarget(String target) {
        if (target == null) return null;
        return MODULE_TARGETS.entrySet().stream().filter(entry -> entry.getValue().contains(target))
                .map(Map.Entry::getKey).findFirst().orElse(target);
    }

    private static String descriptionFrom(String content) {
        if (content == null || content.isBlank()) return "未采集操作内容";
        try {
            var node = JSON.readTree(content);
            return node.has("action") && node.get("action").isTextual()
                    ? node.get("action").asText() : content;
        } catch (Exception ignored) {
            return content;
        }
    }


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
        vo.setOperatorName(entity.getOperatorId() == null ? "系统" : entity.getOperatorType() + "#" + entity.getOperatorId());
        vo.setModule(moduleForTarget(entity.getTargetType()));
        vo.setAction(entity.getOperationType());
        vo.setDescription(descriptionFrom(entity.getContent()));
        vo.setIpAddress(entity.getIp());
        if (entity.getContent() != null) {
            try {
                var params = JSON.readTree(entity.getContent()).get("requestParams");
                if (params != null && params.isObject()) vo.setRequestParams(JSON.writeValueAsString(params));
            } catch (Exception ignored) {
                /* 历史纯文本/未采集日志保留空参数，不补造。 */
            }
        }
        return vo;
    }
}
