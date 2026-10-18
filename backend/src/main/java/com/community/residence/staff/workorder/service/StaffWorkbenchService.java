package com.community.residence.staff.workorder.service;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.workorder.mapper.WorkOrderMapper;
import com.community.residence.common.workorder.service.WorkOrderService;
import com.community.residence.staff.workorder.vo.StaffWorkbenchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** 服务人员专属读取，不接收外部人员ID。 */
@Service
@RequiredArgsConstructor
public class StaffWorkbenchService {
    private final WorkOrderMapper mapper;
    private final WorkOrderService workOrders;
    /* 当前负荷与待办在同一只读事务读取；事件统计继续由统计域按日期计算。 */
    @Transactional(readOnly = true)
    public StaffWorkbenchVO summary() {
        if (!SecurityUtils.hasRole("STAFF")) throw new BusinessException(ErrorCode.FORBIDDEN);
        Long id = SecurityUtils.getUserId();
        var load = mapper.selectCurrentLoad(id);
        var result = new StaffWorkbenchVO();
        if (load != null) {
            result.setTodoCount(load.getTodoCount()); result.setDoingCount(load.getDoingCount());
            result.setCompletedCount(load.getCompletedCount()); result.setUrgentCount(load.getUrgentCount());
        }
        result.setTodoOrders(workOrders.currentStaffTodos(id));
        return result;
    }
}
