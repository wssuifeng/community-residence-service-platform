package com.community.residence.notice.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
@Data @Schema(description="发布后原位修订，仅标题与正文，投放范围和时效不变")
public class ReviseNoticeDTO {
 @Schema(description="基于的正文版本") @NotNull @Min(1) private Integer expectedVersion;
 @Schema(description="标题") @NotBlank @Size(max=200) private String title;
 @Schema(description="受限结构化正文JSON") @NotBlank @Size(max=100000) private String bodyJson;
}
