package com.community.residence.statistics.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.statistics.entity.StatisticsSnapshot;
import org.apache.ibatis.annotations.Mapper;

/* 统计快照数据访问（社区级联删除按 community_id 清理；C9 看板为实时聚合不读本表） */
@Mapper
public interface StatisticsSnapshotMapper extends BaseMapper<StatisticsSnapshot> {
}
