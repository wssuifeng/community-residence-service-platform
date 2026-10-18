package com.community.residence.common.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.conversation.entity.ConversationMessage;
import org.apache.ibatis.annotations.Mapper;

/** 会话消息数据访问 */
@Mapper
public interface ConversationMessageMapper extends BaseMapper<ConversationMessage> {
}
