package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateTimeSlotDTO;
import com.community.residence.community.entity.PublicResource;
import com.community.residence.community.entity.ResourceTimeslot;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.community.vo.TimeSlotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 资源时段业务逻辑：按周循环模板；同资源同星期时段重叠检测 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceTimeslotService {

    private final ResourceTimeslotMapper timeslotMapper;
    private final PublicResourceService publicResourceService;

    @Transactional(rollbackFor = Exception.class)
    public TimeSlotVO create(Long resourceId, CreateTimeSlotDTO dto) {
        PublicResource resource = publicResourceService.requireResource(resourceId);
        SecurityUtils.checkCommunityAccess(resource.getCommunityId());
        validateTimeRange(dto);
        checkOverlap(resourceId, dto.getDayOfWeek(), dto.getStartTime(), dto.getEndTime(), null);

        ResourceTimeslot timeslot = new ResourceTimeslot();
        timeslot.setResourceId(resourceId);
        timeslot.setCommunityId(resource.getCommunityId());
        timeslot.setDayOfWeek(dto.getDayOfWeek());
        timeslot.setStartTime(dto.getStartTime());
        timeslot.setEndTime(dto.getEndTime());
        timeslot.setIsAvailable(dto.getIsAvailable() != null ? dto.getIsAvailable() : 1);
        timeslotMapper.insert(timeslot);
        return TimeSlotVO.from(timeslot);
    }

    @Transactional(rollbackFor = Exception.class)
    public TimeSlotVO update(Long id, CreateTimeSlotDTO dto) {
        ResourceTimeslot timeslot = requireTimeslot(id);
        SecurityUtils.checkCommunityAccess(timeslot.getCommunityId());
        validateTimeRange(dto);
        checkOverlap(timeslot.getResourceId(), dto.getDayOfWeek(), dto.getStartTime(), dto.getEndTime(), id);

        timeslot.setDayOfWeek(dto.getDayOfWeek());
        timeslot.setStartTime(dto.getStartTime());
        timeslot.setEndTime(dto.getEndTime());
        if (dto.getIsAvailable() != null) {
            timeslot.setIsAvailable(dto.getIsAvailable());
        }
        timeslotMapper.updateById(timeslot);
        return TimeSlotVO.from(timeslot);
    }

    /* 删除保护：C7 预约按日期+时段校验冲突，模板时段被引用前不允许物理删除 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ResourceTimeslot timeslot = requireTimeslot(id);
        SecurityUtils.checkCommunityAccess(timeslot.getCommunityId());
        timeslotMapper.deleteById(id);
        log.info("资源时段已删除：timeslotId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /** 资源时段分页列表（公开，可按星期过滤） */
    public PageVO<TimeSlotVO> pageByResource(Long resourceId, long page, long size, Integer dayOfWeek) {
        LambdaQueryWrapper<ResourceTimeslot> wrapper = new LambdaQueryWrapper<ResourceTimeslot>()
                .eq(ResourceTimeslot::getResourceId, resourceId)
                .eq(dayOfWeek != null, ResourceTimeslot::getDayOfWeek, dayOfWeek)
                .orderByAsc(ResourceTimeslot::getDayOfWeek)
                .orderByAsc(ResourceTimeslot::getStartTime);
        Page<ResourceTimeslot> result = timeslotMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(TimeSlotVO::from));
    }

    private ResourceTimeslot requireTimeslot(Long id) {
        ResourceTimeslot timeslot = timeslotMapper.selectById(id);
        if (timeslot == null) {
            throw new ResourceNotFoundException("时段不存在");
        }
        return timeslot;
    }

    private void validateTimeRange(CreateTimeSlotDTO dto) {
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "开始时间必须早于结束时间");
        }
    }

    /* 同资源同星期时段重叠检测：排除自身（更新场景）；半开区间 [start, end) 判定 */
    private void checkOverlap(Long resourceId, Integer dayOfWeek,
                              java.time.LocalTime startTime, java.time.LocalTime endTime, Long excludeId) {
        List<ResourceTimeslot> existing = timeslotMapper.selectList(
                new LambdaQueryWrapper<ResourceTimeslot>()
                        .eq(ResourceTimeslot::getResourceId, resourceId)
                        .eq(ResourceTimeslot::getDayOfWeek, dayOfWeek));
        boolean overlapped = existing.stream()
                .anyMatch(t -> !t.getId().equals(excludeId)
                        && startTime.isBefore(t.getEndTime())
                        && t.getStartTime().isBefore(endTime));
        if (overlapped) {
            throw new BusinessException(ErrorCode.TIMESLOT_CONFLICT);
        }
    }
}
