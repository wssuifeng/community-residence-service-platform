package com.community.residence.common.housing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.housing.dto.CreateHousingTimeslotDTO;
import com.community.residence.common.housing.entity.HousingTimeslot;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 房源时段规则与存储，供管理维护和公共读取共用。 */
@Service
@RequiredArgsConstructor
public class HousingTimeslotService {
    private final HousingTimeslotMapper timeslotMapper;
    private final HousingService housingService;
    /** 保留原时段操作的数据范围与冲突规则。 */
    public HousingTimeslot create(Long housingId, CreateHousingTimeslotDTO dto) {
        var housing = housingService.requireHousing(housingId);
        SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        validate(dto);
        checkOverlap(housingId, dto, null);

        HousingTimeslot timeslot = new HousingTimeslot();
        timeslot.setHousingId(housingId);
        timeslot.setDayOfWeek(dto.getDayOfWeek());
        timeslot.setStartTime(dto.getStartTime());
        timeslot.setEndTime(dto.getEndTime());
        timeslot.setIsAvailable(dto.getIsAvailable() != null ? dto.getIsAvailable() : 1);
        timeslotMapper.insert(timeslot);
        return timeslot;
    }

    /** 保留原时段操作的数据范围与冲突规则。 */
    public HousingTimeslot update(Long id, CreateHousingTimeslotDTO dto) {
        HousingTimeslot timeslot = timeslotMapper.selectById(id);
        if (timeslot == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "时段不存在");
        }
        var housing = housingService.requireHousing(timeslot.getHousingId());
        SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        validate(dto);
        checkOverlap(timeslot.getHousingId(), dto, id);

        timeslot.setDayOfWeek(dto.getDayOfWeek());
        timeslot.setStartTime(dto.getStartTime());
        timeslot.setEndTime(dto.getEndTime());
        if (dto.getIsAvailable() != null) {
            timeslot.setIsAvailable(dto.getIsAvailable());
        }
        timeslotMapper.updateById(timeslot);
        return timeslot;
    }

    /** 保留原时段操作的数据范围与冲突规则。 */
    public void delete(Long id) {
        HousingTimeslot timeslot = timeslotMapper.selectById(id);
        if (timeslot != null) {
            var housing = housingService.requireHousing(timeslot.getHousingId());
            SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        }
        timeslotMapper.deleteById(id);
        return;
    }

    /** 保留原时段操作的数据范围与冲突规则。 */
    public List<HousingTimeslot> list(Long housingId, Integer dayOfWeek) {
        housingService.getById(housingId);
        return timeslotMapper.selectList(
                new LambdaQueryWrapper<HousingTimeslot>()
                        .eq(HousingTimeslot::getHousingId, housingId)
                        .eq(dayOfWeek != null, HousingTimeslot::getDayOfWeek, dayOfWeek)
                        .orderByAsc(HousingTimeslot::getDayOfWeek)
                        .orderByAsc(HousingTimeslot::getStartTime));
    }

    private void validate(CreateHousingTimeslotDTO dto) {
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "开始时间必须早于结束时间");
        }
    }

    private void checkOverlap(Long housingId, CreateHousingTimeslotDTO dto, Long excludeId) {
        List<HousingTimeslot> existing = timeslotMapper.selectList(
                new LambdaQueryWrapper<HousingTimeslot>()
                        .eq(HousingTimeslot::getHousingId, housingId)
                        .eq(HousingTimeslot::getDayOfWeek, dto.getDayOfWeek()));
        boolean overlapped = existing.stream()
                .anyMatch(t -> !t.getId().equals(excludeId)
                        && dto.getStartTime().isBefore(t.getEndTime())
                        && t.getStartTime().isBefore(dto.getEndTime()));
        if (overlapped) {
            throw new BusinessException(ErrorCode.TIMESLOT_CONFLICT);
        }
    }
}
