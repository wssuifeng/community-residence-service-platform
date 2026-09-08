package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.HouseStatusHistory;
import org.apache.ibatis.annotations.Mapper;

/** 房屋状态变更历史数据访问（仅追加） */
@Mapper
public interface HouseStatusHistoryMapper extends BaseMapper<HouseStatusHistory> {
}
