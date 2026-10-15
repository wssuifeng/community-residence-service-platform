package com.community.residence.lease.vo;
import com.community.residence.lease.entity.LeaseContractTerm;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDate;
import java.util.List;
/** 合同快照与真实已缴区间分开返回，不能以最大覆盖止期填平未缴空档。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeaseContractTermVO extends LeaseContractTerm {
    @Schema(description = "本合同段已支付月数，不含处理中或异常单")
    private int paidMonths;
    @Schema(description = "尚未支付月数")
    private int remainingMonths;
    @Schema(description = "最早未缴账期的应付日，未生效或全缴清为空")
    private LocalDate nextDueDate;
    @Schema(description = "已生效合同段是否存在已过应付日的未缴租金")
    private boolean overdue;
    @Schema(description = "逐笔已缴真实区间；不合并跨段空档")
    private List<Coverage> paidPeriods;
    public record Coverage(@Schema(description = "支付单号") String paymentNo,
    @Schema(description = "覆盖开始") LocalDate startDate,
    @Schema(description = "覆盖止日") LocalDate endDate) {
    }
}
