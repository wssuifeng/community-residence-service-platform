package com.community.residence.common.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.feedback.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/** 反馈单数据访问 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
