package com.community.residence.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.conversation.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/** 多方会话数据访问 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
