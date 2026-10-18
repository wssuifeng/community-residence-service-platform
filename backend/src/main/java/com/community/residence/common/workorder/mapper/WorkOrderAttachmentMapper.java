package com.community.residence.common.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.workorder.entity.WorkOrderAttachment;
import org.apache.ibatis.annotations.Mapper;

/** 工单附件表访问 */
@Mapper
public interface WorkOrderAttachmentMapper extends BaseMapper<WorkOrderAttachment> {
}
