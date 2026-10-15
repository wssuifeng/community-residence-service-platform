package com.community.residence.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.conversation.entity.ConversationParticipant;
import org.apache.ibatis.annotations.Mapper;

/** 会话参与者数据访问 */
@Mapper
public interface ConversationParticipantMapper extends BaseMapper<ConversationParticipant> {
    /* 多窗口并发确认只前移；身份空间及会话共同限定，不以读出的旧实体覆盖游标。 */
    @org.apache.ibatis.annotations.Update("UPDATE conversation_participant SET last_read_message_id = GREATEST(COALESCE(last_read_message_id, 0), #{cursor}) WHERE conversation_id = #{conversationId} AND owner_type = #{ownerType} AND user_id = #{userId}")
    int advanceReadCursor(@org.apache.ibatis.annotations.Param("conversationId") Long conversationId,
                          @org.apache.ibatis.annotations.Param("ownerType") String ownerType,
                          @org.apache.ibatis.annotations.Param("userId") Long userId,
                          @org.apache.ibatis.annotations.Param("cursor") Long cursor);

}
