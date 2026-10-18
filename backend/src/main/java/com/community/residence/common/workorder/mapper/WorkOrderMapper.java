package com.community.residence.common.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.workorder.entity.WorkOrder;
import org.apache.ibatis.annotations.Mapper;

/** 工单数据访问 */
@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}
