package com.community.residence.common.workorder.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
/** 最新派单归属下的当前工单负荷，不表示事件次数。 */
@Data
public class WorkOrderLoadVO {
    @Schema(description="已派单数") private long todoCount;
    @Schema(description="已接单与处理中数") private long doingCount;
    @Schema(description="居民已确认完成数，非当日统计") private long completedCount;
    @Schema(description="紧急且待接单数") private long urgentCount;
}
