package com.community.residence.common.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import org.apache.ibatis.annotations.Mapper;

/** 资源预约数据访问（冲突检测见 Service 层组合查询） */
@Mapper
public interface ResourceReservationMapper extends BaseMapper<ResourceReservation> {
    /* 所有状态动作共用预约行锁，校验当前状态后才更新与产生副作用。 */
    @org.apache.ibatis.annotations.Select("SELECT id, user_id, resource_id, community_id, reserve_date, "
            + "start_time, end_time, status, purpose, contact_phone, remark, created_at, updated_at "
            + "FROM resource_reservation WHERE id = #{id} FOR UPDATE")
    ResourceReservation selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

}
