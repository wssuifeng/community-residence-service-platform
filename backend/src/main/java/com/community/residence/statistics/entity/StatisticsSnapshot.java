package com.community.residence.statistics.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 统计快照（statistics_snapshot 表，可选预聚合表；C9 看板实时聚合，本实体服务于社区级联删除 V1 第 29 表） */
@Data
@TableName("statistics_snapshot")
@Schema(description = "统计快照")
public class StatisticsSnapshot {

    @TableId(type = IdType.AUTO)
    @Schema(description = "快照ID")
    private Long id;

    @Schema(description = "所属社区ID")
    private Long communityId;

    @Schema(description = "快照日期")
    private LocalDate snapshotDate;

    @Schema(description = "指标类型")
    private String metricType;

    @Schema(description = "指标值")
    private Long metricValue;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
