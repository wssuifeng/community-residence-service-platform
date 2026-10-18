package com.community.residence.common.evaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.evaluation.entity.UnsatisfiedFollowup;
import org.apache.ibatis.annotations.Mapper;

/** 不满意跟进记录数据访问 */
@Mapper
public interface UnsatisfiedFollowupMapper extends BaseMapper<UnsatisfiedFollowup> {
}
