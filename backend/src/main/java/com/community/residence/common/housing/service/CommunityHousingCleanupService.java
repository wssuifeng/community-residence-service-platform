package com.community.residence.common.housing.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.entity.HousingTimeslot;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.reservation.entity.ViolationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 小区删除时的housing域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityHousingCleanupService {
    private final ViolationRecordMapper violationRecordMapper;
    private final HousingMapper housingMapper;
    private final HousingTimeslotMapper housingTimeslotMapper;
    private final ViewingAppointmentMapper viewingAppointmentMapper;
    private final com.community.residence.common.resident.service.CommunityResidentCleanupService residentCleanup;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeHousing(Long id) {
        /* ---- C12 房源链（V1 #37~39）：时段/看房预约 → 房源；看房违约记录随之清理 ---- */
        List<Long> housingIds = housingMapper.selectList(
                new LambdaQueryWrapper<Housing>().eq(Housing::getCommunityId, id))
                .stream().map(Housing::getId).toList();
        residentCleanup.removeApplications(id);
        if (!housingIds.isEmpty()) {
            housingTimeslotMapper.delete(new LambdaQueryWrapper<HousingTimeslot>()
                    .in(HousingTimeslot::getHousingId, housingIds));
            viewingAppointmentMapper.delete(new LambdaQueryWrapper<ViewingAppointment>()
                    .in(ViewingAppointment::getHousingId, housingIds));
            housingMapper.deleteBatchIds(housingIds);
        }
        List<Long> viewingIds = viewingAppointmentMapper.selectList(
                new LambdaQueryWrapper<ViewingAppointment>()
                        .eq(ViewingAppointment::getCommunityId, id))
                .stream().map(ViewingAppointment::getId).toList();
        if (!viewingIds.isEmpty()) {
            violationRecordMapper.delete(new LambdaQueryWrapper<ViolationRecord>()
                    .in(ViolationRecord::getRelatedId, viewingIds));
            viewingAppointmentMapper.deleteBatchIds(viewingIds);
        }

    }
}
