package com.community.residence.community.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "批量时段创建结果")
public record BatchTimeslotVO(
    @Schema(description = "新建星期") List<Integer> createdDays,
    @Schema(description = "已存在且未改变的星期") List<Integer> skippedDays) { }
