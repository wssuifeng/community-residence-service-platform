package com.community.residence.common.statistics.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.statistics.entity.StatisticsSnapshot;
import com.community.residence.common.statistics.mapper.StatisticsSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 社区删除时的statistics域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityStatisticsCleanupService {
    private final StatisticsSnapshotMapper statisticsSnapshotMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeStatistics(Long id) {
        /* ---- 横切与统计（V1 #29/35）：统计快照、通知及渠道留痕 ---- */
        statisticsSnapshotMapper.delete(new LambdaQueryWrapper<StatisticsSnapshot>()
                .eq(StatisticsSnapshot::getCommunityId, id));
    }
}
