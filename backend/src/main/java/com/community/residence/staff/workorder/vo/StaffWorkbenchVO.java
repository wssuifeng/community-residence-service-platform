package com.community.residence.staff.workorder.vo;
import com.community.residence.common.workorder.vo.WorkOrderVO;
import com.community.residence.common.workorder.vo.WorkOrderLoadVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
/** 服务人员工作台当前负荷与有界待办。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StaffWorkbenchVO extends WorkOrderLoadVO {
    @Schema(description="三个待办状态各最多五条，紧急优先") private List<WorkOrderVO> todoOrders;
}
