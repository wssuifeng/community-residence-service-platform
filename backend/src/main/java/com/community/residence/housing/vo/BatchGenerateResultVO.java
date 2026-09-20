package com.community.residence.housing.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 按社区批量挂牌结果（R62：幂等——已有在架房源的房屋跳过） */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "按社区批量挂牌结果")
public class BatchGenerateResultVO {

    @Schema(description = "新建房源数")
    private Integer created;

    @Schema(description = "跳过数（已有在架房源的房屋）")
    private Integer skipped;

    @Schema(description = "新建可预约看房时段数（createTimeslots=false 时为 0；跳过的房屋不建时段）")
    private Integer timeslotsCreated;

    public static BatchGenerateResultVO of(int created, int skipped, int timeslotsCreated) {
        return new BatchGenerateResultVO(created, skipped, timeslotsCreated);
    }
}
