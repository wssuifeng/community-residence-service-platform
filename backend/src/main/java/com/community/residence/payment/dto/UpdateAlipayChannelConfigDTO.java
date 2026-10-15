package com.community.residence.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.ToString;

@Data
@Schema(description = "超管支付宝配置与启用控制；保存仅离线校验")
public class UpdateAlipayChannelConfigDTO {
    @NotBlank @Pattern(regexp = "[0-9]{16,32}", message = "APPID 必须为16~32位数字")
    @Schema(description = "支付宝 APPID") private String appId;
    @NotBlank @Size(max = 8192) @Schema(description = "应用公钥（X.509/PEM）") private String applicationPublicKey;
    @Size(max = 8192) @ToString.Exclude @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "应用私钥（PKCS8/PEM）；空白保留旧值", accessMode = Schema.AccessMode.WRITE_ONLY) private String merchantPrivateKey;
    @NotBlank @Size(max = 8192) @Schema(description = "支付宝公钥（非应用公钥）") private String alipayPublicKey;
    @NotBlank @Pattern(regexp = "SANDBOX|PRODUCTION") @Schema(description = "环境，默认沙箱") private String environment;
    @NotNull @Min(0) @Schema(description = "读取时的配置版本，防覆盖") private Long expectedRevision;
    @Schema(description = "是否启用渠道；省略保留原状态，清除私钥自动停用") private Boolean enabled;
    @Schema(description = "明确清除私钥，与新私钥互斥") private boolean clearPrivateKey;
}
