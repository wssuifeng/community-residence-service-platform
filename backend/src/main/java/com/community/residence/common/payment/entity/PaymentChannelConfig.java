package com.community.residence.common.payment.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.ToString;
import java.time.LocalDateTime;

@Data
@TableName("payment_channel_config")
@Schema(description = "独立支付渠道配置，不通过公开配置接口读取")
public class PaymentChannelConfig {
    @TableId(type = IdType.AUTO) @Schema(description = "配置ID") private Long id;
    @Schema(description = "渠道标识") private String channel;
    @Schema(description = "SANDBOX/PRODUCTION") private String environment;
    @Schema(description = "支付宝 APPID") private String appId;
    @Schema(description = "应用公钥") private String applicationPublicKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) @ToString.Exclude @Schema(description = "加密私钥，只在内部使用") private String merchantPrivateKeyCiphertext;
    @Schema(description = "支付宝公钥") private String alipayPublicKey;
    @Schema(description = "渠道是否启用，本批保持停用") private Boolean enabled;
    @Schema(description = "配置版本") private Long revision;
    @Schema(description = "更新超管ID") private Long updatedBy;
    @Schema(description = "创建时间") private LocalDateTime createdAt;
    @Schema(description = "更新时间") private LocalDateTime updatedAt;
}
