package com.community.residence.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.feedback.entity.FeedbackMessage;
import org.apache.ibatis.annotations.Mapper;

/** 反馈会话消息数据访问 */
@Mapper
public interface FeedbackMessageMapper extends BaseMapper<FeedbackMessage> {
}
