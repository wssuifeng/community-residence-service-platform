package com.community.residence.payment.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Schema(description = "支付宝渠道配置；不含应用私钥或密文")
public class AlipayChannelConfigVO {
    @Schema(description = "APPID") private String appId;
    @Schema(description = "应用公钥") private String applicationPublicKey;
    @Schema(description = "支付宝公钥") private String alipayPublicKey;
    @Schema(description = "环境") private String environment;
    @Schema(description = "私钥已保存，不回显内容") private boolean privateKeyConfigured;
    @Schema(description = "四项配置已保存，不代表渠道已验证") private boolean configured;
    @Schema(description = "渠道启用状态，本批固定停用") private boolean enabled;
    @Schema(description = "配置版本") private long revision;
    @Schema(description = "更新时间") private LocalDateTime updatedAt;
}
