package com.community.residence.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.feedback.entity.FeedbackAttachment;
import org.apache.ibatis.annotations.Mapper;

/** 反馈附件表访问 */
@Mapper
public interface FeedbackAttachmentMapper extends BaseMapper<FeedbackAttachment> {
}
