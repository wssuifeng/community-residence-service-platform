package com.community.residence.workorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 排班批量保存/清空结果（saved 为实际写入或清除的记录数） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "排班批量操作结果")
public class BatchSaveScheduleResultVO {

    @Schema(description = "写入或清除的记录数", example = "10")
    private int saved;
}
