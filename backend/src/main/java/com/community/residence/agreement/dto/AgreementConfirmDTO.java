package com.community.residence.agreement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 协议确认请求（居民方/管理方各自调用，服务端按当前身份落到对应方） */
@Data
@Schema(description = "协议确认请求")
public class AgreementConfirmDTO {

    @Schema(description = "确认人姓名（缺省取当前账号姓名，快照入库）")
    @Size(max = 50, message = "确认人姓名最多 50 字符")
    private String confirmName;

    @Schema(description = "确认备注")
    @Size(max = 500, message = "备注最多 500 字符")
    private String remark;
}
