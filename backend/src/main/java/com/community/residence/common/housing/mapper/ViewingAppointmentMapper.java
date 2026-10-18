package com.community.residence.common.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.housing.entity.ViewingAppointment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

/** 看房预约数据访问 */
@Mapper
public interface ViewingAppointmentMapper extends BaseMapper<ViewingAppointment> {

    /* 分配/完成归属校验与违约入账共用行锁，重复状态动作不得重复通知或增加次数。 */
    @Select("SELECT id, user_id, visitor_name, visitor_phone, assigned_staff_id, conversation_id, lease_id, "
            + "housing_id, community_id, appointment_date, start_time, end_time, status, "
            + "contact_phone, remark, created_at, updated_at FROM viewing_appointment WHERE id = #{id} FOR UPDATE")
    ViewingAppointment selectByIdForUpdate(@Param("id") Long id);


    /*
     * 看房 → 租约 指向修正（DEF-081）：审批建租约时，把该居民该房屋下
     * **尚未指向任何租约**（lease_id 为空）的看房预约补写成本次生成的租约。
     * 背景：房源页发起的看房不写 lease_id，导致"这次看房算不算那条租约的"只能靠
     * 「同一居民 + 同一房源」反查（同屋多租约时张冠李戴）。
     * 幂等：`lease_id IS NULL` 保证重复审批不重写、不覆盖既有非空指向；
     * 只改指向字段，不动看房自身状态（看房仍只是沟通手段，不是承约门槛）。
     * 游客预约（user_id 为空）不命中。
     */
    @Update("UPDATE viewing_appointment va "
            + "SET va.lease_id = #{leaseId}, va.updated_at = NOW() "
            + "WHERE va.lease_id IS NULL "
            + "  AND va.user_id = #{residentId} "
            + "  AND va.housing_id IN (SELECT h.id FROM housing h WHERE h.house_id = #{houseId})")
    int linkToLease(@Param("leaseId") Long leaseId,
                    @Param("residentId") Long residentId,
                    @Param("houseId") Long houseId);
}
