package com.community.residence.notice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公告发布请求（重新指定发布时间） */
@Data
@Schema(description = "公告发布请求")
public class PublishNoticeDTO {

    @Schema(description = "发布时间")
    @NotBlank(message = "发布时间不能为空")
    @Size(max = 30, message = "发布时间格式不合法")
    private String publishTime;
}
