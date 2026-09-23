package com.community.residence.lease.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 创建退租申请请求（R70：居民对本人已生效租约发起，搬出日期 + 原因） */
@Data
@Schema(description = "创建退租申请请求")
public class CreateMoveOutApplicationDTO {

    @Schema(description = "申请搬出日期")
    @NotNull(message = "搬出日期不能为空")
    @FutureOrPresent(message = "退租日期不得早于今天")
    private LocalDate moveOutDate;

    @Schema(description = "退租原因")
    @Size(max = 500, message = "退租原因最多 500 字符")
    private String reason;
}
