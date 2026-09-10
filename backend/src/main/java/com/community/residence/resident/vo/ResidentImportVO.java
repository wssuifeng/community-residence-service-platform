package com.community.residence.resident.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/** CSV 批量导入居民结果（部分成功语义：成功行生效、失败行带行号与原因，R8 v1.2） */
@Data
@Schema(description = "批量导入居民结果")
public class ResidentImportVO {

    @Schema(description = "总行数（不含表头）")
    private int total;

    @Schema(description = "成功行数")
    private int success;

    @Schema(description = "失败行数")
    private int fail;

    @Schema(description = "成功明细（行号 + 用户名 + 初始密码）")
    private List<SuccessRow> successRows;

    @Schema(description = "失败明细（行号 + 原因）")
    private List<FailRow> failRows;

    @Data
    @Schema(description = "导入成功行")
    public static class SuccessRow {

        @Schema(description = "行号（数据行，从 1 计，不含表头）")
        private int row;

        @Schema(description = "用户名")
        private String username;

        @Schema(description = "初始密码（明文仅本次返回）")
        private String initialPassword;

        public static SuccessRow of(int row, String username, String initialPassword) {
            SuccessRow r = new SuccessRow();
            r.setRow(row);
            r.setUsername(username);
            r.setInitialPassword(initialPassword);
            return r;
        }
    }

    @Data
    @Schema(description = "导入失败行")
    public static class FailRow {

        @Schema(description = "行号（数据行，从 1 计，不含表头）")
        private int row;

        @Schema(description = "失败原因")
        private String reason;

        public static FailRow of(int row, String reason) {
            FailRow r = new FailRow();
            r.setRow(row);
            r.setReason(reason);
            return r;
        }
    }
}
