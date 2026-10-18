package com.community.residence.common.conversation.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 社区删除时的conversation域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityConversationCleanupService {
    private final com.community.residence.common.conversation.mapper.ChatMessageImageMapper chatMessageImageMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeImages(Long id) {
        chatMessageImageMapper.delete(new LambdaQueryWrapper<com.community.residence.common.conversation.entity.ChatMessageImage>()
                .eq(com.community.residence.common.conversation.entity.ChatMessageImage::getCommunityId, id));

    }
}
