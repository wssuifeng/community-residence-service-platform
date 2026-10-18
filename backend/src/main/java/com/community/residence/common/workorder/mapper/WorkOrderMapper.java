package com.community.residence.common.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.workorder.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;

/** 工单数据访问 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
    /* 最新派单决定归属；历史曾派给本人不计入当前负荷。 */
    String CURRENT_ASSIGNEE = " EXISTS (SELECT 1 FROM work_order_assignment a WHERE a.work_order_id = w.id "
            + "AND a.assignee_id = #{staffId} AND a.id = (SELECT MAX(b.id) FROM work_order_assignment b WHERE b.work_order_id = w.id)) ";

    @org.apache.ibatis.annotations.Select("SELECT COALESCE(SUM(w.status='ASSIGNED'),0) todo_count, "
            + "COALESCE(SUM(w.status IN ('ACCEPTED','IN_PROGRESS')),0) doing_count, "
            + "COALESCE(SUM(w.status='COMPLETED'),0) completed_count, "
            + "COALESCE(SUM(w.status='ASSIGNED' AND w.priority='URGENT'),0) urgent_count "
            + "FROM work_order w WHERE " + CURRENT_ASSIGNEE)
    com.community.residence.common.workorder.vo.WorkOrderLoadVO selectCurrentLoad(@org.apache.ibatis.annotations.Param("staffId") Long staffId);

    /* 每个待办状态最多五条，保持旧工作台选择范围；装配详情时批量读取关联数据。 */
    @org.apache.ibatis.annotations.Select("SELECT ranked.* FROM (SELECT w.*, ROW_NUMBER() OVER (PARTITION BY w.status ORDER BY w.id DESC) rn "
            + "FROM work_order w WHERE w.status IN ('ASSIGNED','ACCEPTED','IN_PROGRESS') AND " + CURRENT_ASSIGNEE
            + ") ranked WHERE rn <= 5 ORDER BY CASE priority WHEN 'URGENT' THEN 3 WHEN 'HIGH' THEN 2 WHEN 'NORMAL' THEN 1 ELSE 0 END DESC, created_at DESC, id DESC")
    java.util.List<WorkOrder> selectCurrentTodos(@org.apache.ibatis.annotations.Param("staffId") Long staffId);

}
