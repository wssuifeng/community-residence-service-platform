package com.community.residence.messaging.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 通知渠道分级配置请求（R51：按通知等级勾选可插拔模拟渠道；站内 WEBSOCKET 必达不可配置；
 *  合法渠道 EMAIL/SMS 由服务端清单校验） */
@Data
@Schema(description = "通知渠道分级配置请求")
public class ChannelLevelsDTO {

    @Schema(description = "等级 → 勾选渠道列表（渠道仅可填 EMAIL/SMS，站内必达）",
            example = "{\"IMPORTANT\":[\"SMS\"],\"NORMAL\":[]}")
    @NotNull(message = "渠道分级映射不能为空")
    private Map<String, List<String>> levels;
}
