package com.community.residence.common.reservation.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.reservation.entity.ResourceReservation;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.reservation.entity.ViolationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 小区删除时的reservation域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityReservationCleanupService {
    private final ResourceReservationMapper resourceReservationMapper;
    private final ViolationRecordMapper violationRecordMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeReservations(Long id) {
        /* ---- C7 预约与违约（V1 #25/26）：违约记录按本小区预约/看房预约 related_id 清理 → 预约
                （violation_record 无 FK，RESERVATION_NO_SHOW 关联资源预约、VIEWING_NO_SHOW 关联看房预约） ---- */
        List<Long> reservationIds = resourceReservationMapper.selectList(
                new LambdaQueryWrapper<ResourceReservation>()
                        .eq(ResourceReservation::getCommunityId, id))
                .stream().map(ResourceReservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            violationRecordMapper.delete(new LambdaQueryWrapper<ViolationRecord>()
                    .in(ViolationRecord::getRelatedId, reservationIds));
            resourceReservationMapper.deleteBatchIds(reservationIds);
        }

    }
}
