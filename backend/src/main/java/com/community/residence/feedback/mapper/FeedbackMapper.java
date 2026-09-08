package com.community.residence.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.feedback.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/** 反馈单数据访问 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
}
