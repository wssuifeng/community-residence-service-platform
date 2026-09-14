package com.community.residence.community.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** 批量创建结果（D-端点2，部分成功语义对齐 R8 CSV 导入先例：逐行反馈） */
@Data
@Schema(description = "批量创建结果")
public class BatchCreateResultVO {

    @Schema(description = "总行数")
    private Integer total;

    @Schema(description = "成功行数")
    private Integer success;

    @Schema(description = "失败行数")
    private Integer fail;

    @Schema(description = "逐行结果")
    private List<Row> rows;

    public static BatchCreateResultVO of(int total, int success, List<Row> rows) {
        BatchCreateResultVO vo = new BatchCreateResultVO();
        vo.setTotal(total);
        vo.setSuccess(success);
        vo.setFail(total - success);
        vo.setRows(rows);
        return vo;
    }

    /** 逐行结果：行号 + 成功ID 或失败原因 */
    @Data
    @Schema(description = "批量创建逐行结果")
    public static class Row {

        @Schema(description = "行号（1 起）")
        private Integer rowNo;

        @Schema(description = "是否成功")
        private Boolean success;

        @Schema(description = "成功时的新建ID")
        private Long id;

        @Schema(description = "失败原因（失败时）")
        private String reason;

        public static Row success(int rowNo, Long id) {
            Row row = new Row();
            row.setRowNo(rowNo);
            row.setSuccess(true);
            row.setId(id);
            return row;
        }

        public static Row fail(int rowNo, String reason) {
            Row row = new Row();
            row.setRowNo(rowNo);
            row.setSuccess(false);
            row.setReason(reason);
            return row;
        }
    }
}
