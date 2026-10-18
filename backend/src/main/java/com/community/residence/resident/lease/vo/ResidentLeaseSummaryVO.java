package com.community.residence.resident.lease.vo;
import com.community.residence.common.lease.vo.LeaseVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
/** 本人租约卡片，原租约字段与派生摘要分开。 */
@Data
public class ResidentLeaseSummaryVO {
    @Schema(description="原租约详情字段") private LeaseVO lease;
    @Schema(description="本地合同及支付快照摘要") private Summary summary;
    public record Action(@Schema(description="当前规则允许操作") boolean enabled, @Schema(description="禁用原因或操作说明") String reason) { }
    public record Summary(@Schema(description="展示状态") String displayStatus,
        @Schema(description="正常首期成功") boolean firstPaymentSucceeded,
        @Schema(description="首期成功金额") BigDecimal firstPaymentPaidAmount,
        @Schema(description="首期成功时间") LocalDateTime firstPaymentPaidAt,
        @Schema(description="已缴合同月数，未知为空") Integer paidRentMonths,
        @Schema(description="尚未缴纳合同月数，未知为空") Integer unpaidRentMonths,
        @Schema(description="四项操作可用性") Map<String,Action> actions) { }
}
