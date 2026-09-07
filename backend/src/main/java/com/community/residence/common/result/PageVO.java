package com.community.residence.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应（接口设计.md §2.2：records/total/page/size/pages）。
 * 分页参数约定：page 从 1 开始，size 默认 20、最大 100。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分页数据")
public class PageVO<T> {

    @Schema(description = "当前页数据列表")
    private List<T> records;

    @Schema(description = "总记录数", example = "100")
    private long total;

    @Schema(description = "当前页码（从 1 开始）", example = "1")
    private long page;

    @Schema(description = "每页大小", example = "20")
    private long size;

    @Schema(description = "总页数", example = "5")
    private long pages;

    public static <T> PageVO<T> of(IPage<T> page) {
        return new PageVO<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public static <T> PageVO<T> of(List<T> records, long total, long page, long size) {
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageVO<>(records, total, page, size, pages);
    }
}
