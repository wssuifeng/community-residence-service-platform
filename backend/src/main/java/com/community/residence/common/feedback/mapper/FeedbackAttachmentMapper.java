package com.community.residence.common.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.feedback.entity.FeedbackAttachment;
import org.apache.ibatis.annotations.Mapper;

/** 反馈附件表访问 */
@Mapper
public interface FeedbackAttachmentMapper extends BaseMapper<FeedbackAttachment> {
}
