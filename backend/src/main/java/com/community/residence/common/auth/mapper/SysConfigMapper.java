package com.community.residence.common.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.auth.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/** 全局配置数据访问 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
