package com.community.residence.notice.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 公告查看记录响应 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "公告查看记录")
public class NoticeViewRecordVO {

    @Schema(description = "居民ID")
    private Long residentId;

    @Schema(description = "居民姓名")
    private String residentName;

    @Schema(description = "查看时间")
    private LocalDateTime viewedAt;
}
