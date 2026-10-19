package com.community.residence.common.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.feedback.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;

/** 反馈单数据访问 */
@Mapper
public interface FeedbackMapper extends BaseMapper<Feedback> {
    /* 会话消息/附件写入与办结共用父行锁，不允许闭合后被旧状态覆盖或继续写入。 */
    @org.apache.ibatis.annotations.Select("SELECT id, resident_id, is_anonymous, community_id, title, content, "
            + "category, status, handler_id, created_at, updated_at FROM feedback WHERE id = #{id} FOR UPDATE")
    Feedback selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

}
