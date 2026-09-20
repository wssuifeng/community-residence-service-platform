package com.community.residence.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.conversation.entity.ConversationParticipant;
import org.apache.ibatis.annotations.Mapper;

/** 会话参与者数据访问 */
@Mapper
public interface ConversationParticipantMapper extends BaseMapper<ConversationParticipant> {
}
