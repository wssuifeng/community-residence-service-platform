package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.WorkOrderAttachment;
import org.apache.ibatis.annotations.Mapper;

/** 工单附件表访问 */
@Mapper
public interface WorkOrderAttachmentMapper extends BaseMapper<WorkOrderAttachment> {
}
