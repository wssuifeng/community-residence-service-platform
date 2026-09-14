package com.community.residence.resident.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/** 管理员直建居住关系请求（D-端点3，50 阶段第三批）：直接建立关系不走审批流 */
@Data
@Schema(description = "管理员直建居住关系请求")
public class AdminCreateRelationDTO {

    @Schema(description = "居民ID")
    @NotNull(message = "居民不能为空")
    private Long residentId;

    @Schema(description = "房屋ID")
    @NotNull(message = "房屋不能为空")
    private Long houseId;

    @Schema(description = "关系类型：OWNER-业主, TENANT-租客, FAMILY-家属")
    @NotNull(message = "关系类型不能为空")
    @Pattern(regexp = "^(OWNER|TENANT|FAMILY)$", message = "关系类型取值 OWNER/TENANT/FAMILY")
    private String relationType;

    @Schema(description = "入住日期")
    @NotNull(message = "入住日期不能为空")
    @PastOrPresent(message = "入住日期不能晚于今天")
    private LocalDate moveInDate;

    @Schema(description = "备注（写入操作日志）")
    @Size(max = 200, message = "备注最多 200 字符")
    private String remark;
}
