package com.community.residence.community.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 批量操作结果（社区批量删除/批量状态变更）：成功 ID 列表 + 逐项失败原因 */
@Data
@Schema(description = "批量操作结果")
public class BatchOperationResultVO {

    @Schema(description = "成功处理的社区ID列表（按请求顺序）")
    private List<Long> successIds = new ArrayList<>();

    @Schema(description = "失败项（逐项独立事务，一个失败不影响其余社区）")
    private List<Failure> failures = new ArrayList<>();

    public static BatchOperationResultVO of(List<Long> successIds, List<Failure> failures) {
        BatchOperationResultVO vo = new BatchOperationResultVO();
        vo.setSuccessIds(successIds);
        vo.setFailures(failures);
        return vo;
    }

    /** 失败项：社区 ID + 名称（可回查时为名称，不存在则为空）+ 原因 */
    @Data
    @Schema(description = "批量操作失败项")
    public static class Failure {

        @Schema(description = "社区ID")
        private Long id;

        @Schema(description = "社区名称（存在该社区时可回查，否则为空）")
        private String name;

        @Schema(description = "失败原因")
        private String reason;

        public static Failure of(Long id, String name, String reason) {
            Failure failure = new Failure();
            failure.setId(id);
            failure.setName(name);
            failure.setReason(reason);
            return failure;
        }
    }
}
