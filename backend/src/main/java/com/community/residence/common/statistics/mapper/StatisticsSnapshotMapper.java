package com.community.residence.common.statistics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.statistics.entity.StatisticsSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.LocalDate;
import java.util.List;

/* 统计快照数据访问（社区级联删除按 community_id 清理；C9 看板为实时聚合不读本表） */
@Mapper
public interface StatisticsSnapshotMapper extends BaseMapper<StatisticsSnapshot> {

    @Insert("INSERT INTO statistics_snapshot (community_id, snapshot_date, metric_type, metric_value, created_at) "
            + "VALUES (#{communityId}, #{snapshotDate}, 'HOUSING_VIEW_DAILY', #{delta}, NOW()) "
            + "ON DUPLICATE KEY UPDATE metric_value = metric_value + VALUES(metric_value)")
    int incrementHousingViews(@Param("communityId") Long communityId,
                              @Param("snapshotDate") LocalDate snapshotDate,
                              @Param("delta") long delta);

    @Select("SELECT snapshot_date, metric_value FROM statistics_snapshot "
            + "WHERE metric_type = 'HOUSING_VIEW_DAILY' AND snapshot_date >= #{startDate} "
            + "AND snapshot_date < #{endExclusive} "
            + "AND (#{communityId} IS NULL OR community_id = #{communityId})")
    List<StatisticsSnapshot> selectHousingViews(@Param("communityId") Long communityId,
                                               @Param("startDate") LocalDate startDate,
                                               @Param("endExclusive") LocalDate endExclusive);
}
