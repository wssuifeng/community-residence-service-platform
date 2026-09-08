package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.WorkOrderProcess;
import org.apache.ibatis.annotations.Mapper;

/** 工单处理记录数据访问（仅追加） */
@Mapper
public interface WorkOrderProcessMapper extends BaseMapper<WorkOrderProcess> {
}
