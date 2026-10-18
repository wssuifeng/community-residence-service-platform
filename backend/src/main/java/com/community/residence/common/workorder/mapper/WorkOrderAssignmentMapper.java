package com.community.residence.common.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.workorder.entity.WorkOrderAssignment;
import org.apache.ibatis.annotations.Mapper;

/** 工单派单关系数据访问 */
@Mapper
public interface WorkOrderAssignmentMapper extends BaseMapper<WorkOrderAssignment> {
}
