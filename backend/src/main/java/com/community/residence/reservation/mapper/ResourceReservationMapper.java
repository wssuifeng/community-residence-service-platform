package com.community.residence.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.reservation.entity.ResourceReservation;
import org.apache.ibatis.annotations.Mapper;

/** 资源预约数据访问（冲突检测见 Service 层组合查询） */
@Mapper
public interface ResourceReservationMapper extends BaseMapper<ResourceReservation> {
}
