package com.community.residence.common.notice.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.notice.entity.NoticeTarget;
import com.community.residence.common.notice.entity.NoticeViewRecord;
import com.community.residence.common.notice.mapper.NoticeMapper;
import com.community.residence.common.notice.mapper.NoticeTargetMapper;
import com.community.residence.common.notice.mapper.NoticeViewRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 社区删除时的notice域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityNoticeCleanupService {
    private final NoticeMapper noticeMapper;
    private final NoticeTargetMapper noticeTargetMapper;
    private final NoticeViewRecordMapper noticeViewRecordMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeNotices(Long id) {
        /* ---- 公告链（V1 #19~21）：查看记录/目标 → 公告
                （notice 无 community_id，经 notice_target(target_type=COMMUNITY) 定向归属） ---- */
        List<Long> noticeIds = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getTargetType, "COMMUNITY")
                        .eq(NoticeTarget::getTargetId, id))
                .stream().map(NoticeTarget::getNoticeId).toList();
        if (!noticeIds.isEmpty()) {
            noticeViewRecordMapper.delete(new LambdaQueryWrapper<NoticeViewRecord>()
                    .in(NoticeViewRecord::getNoticeId, noticeIds));
            noticeTargetMapper.delete(new LambdaQueryWrapper<NoticeTarget>()
                    .in(NoticeTarget::getNoticeId, noticeIds));
            noticeMapper.deleteBatchIds(noticeIds);
        }

    }
}
