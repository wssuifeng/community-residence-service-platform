package com.community.residence.common.feedback.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.feedback.entity.Feedback;
import com.community.residence.common.feedback.entity.FeedbackAttachment;
import com.community.residence.common.feedback.entity.FeedbackMessage;
import com.community.residence.common.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.common.feedback.mapper.FeedbackMapper;
import com.community.residence.common.feedback.mapper.FeedbackMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 社区删除时的feedback域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityFeedbackCleanupService {
    private final FeedbackMapper feedbackMapper;
    private final FeedbackMessageMapper feedbackMessageMapper;
    private final FeedbackAttachmentMapper feedbackAttachmentMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeFeedback(Long id) {
        /* ---- 反馈链（V1 #22~24）：消息/附件 → 反馈单 ---- */
        List<Long> feedbackIds = feedbackMapper.selectList(
                new LambdaQueryWrapper<Feedback>().eq(Feedback::getCommunityId, id))
                .stream().map(Feedback::getId).toList();
        if (!feedbackIds.isEmpty()) {
            feedbackMessageMapper.delete(new LambdaQueryWrapper<FeedbackMessage>()
                    .in(FeedbackMessage::getFeedbackId, feedbackIds));
            feedbackAttachmentMapper.delete(new LambdaQueryWrapper<FeedbackAttachment>()
                    .in(FeedbackAttachment::getFeedbackId, feedbackIds));
            feedbackMapper.deleteBatchIds(feedbackIds);
        }

    }
}
