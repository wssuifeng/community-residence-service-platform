package com.community.residence.common.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.dto.CreateTimeSlotDTO;
import com.community.residence.common.community.entity.PublicResource;
import com.community.residence.common.community.entity.ResourceTimeslot;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.community.vo.TimeSlotVO;
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
    private final com.community.residence.common.reservation.mapper.ResourceReservationMapper reservationMapper;

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE", targetType = "RESOURCE_TIMESLOT", targetId = "#result.id", communityId = "#result.communityId", content = "'创建资源时段'")
    public TimeSlotVO create(Long resourceId, CreateTimeSlotDTO dto) {
        PublicResource resource = publicResourceService.requireResourceLocked(resourceId);
        SecurityUtils.checkCommunityAccess(resource.getCommunityId());
        validateTimeRange(dto);
        checkSlotAlignment(resource, dto);
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

    /* 同资源行锁下先预检整批，完全相同跳过（包括停用模板），任一部分重叠则零写入。 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE", targetType = "RESOURCE_TIMESLOT", targetId = "#resourceId", content = "'批量创建周循环资源时段'")
    public com.community.residence.common.community.vo.BatchTimeslotVO createBatch(Long resourceId,
            com.community.residence.common.community.dto.BatchCreateTimeslotDTO dto) {
        PublicResource resource = publicResourceService.requireResourceLocked(resourceId);
        SecurityUtils.checkCommunityAccess(resource.getCommunityId());
        CreateTimeSlotDTO common = new CreateTimeSlotDTO();
        common.setStartTime(dto.getStartTime()); common.setEndTime(dto.getEndTime());
        common.setIsAvailable(dto.getIsAvailable());
        validateTimeRange(common); checkSlotAlignment(resource, common);
        List<Integer> days = dto.getDaysOfWeek().stream().distinct().sorted().toList();
        List<ResourceTimeslot> existing = timeslotMapper.selectList(new LambdaQueryWrapper<ResourceTimeslot>()
                .eq(ResourceTimeslot::getResourceId, resourceId).in(ResourceTimeslot::getDayOfWeek, days).last("FOR UPDATE"));
        List<Integer> created = new java.util.ArrayList<>();
        List<Integer> skipped = new java.util.ArrayList<>();
        for (Integer day : days) {
            List<ResourceTimeslot> overlapping = existing.stream().filter(t -> day.equals(t.getDayOfWeek())
                    && dto.getStartTime().isBefore(t.getEndTime()) && t.getStartTime().isBefore(dto.getEndTime())).toList();
            boolean partial = overlapping.stream().anyMatch(t -> !dto.getStartTime().equals(t.getStartTime())
                    || !dto.getEndTime().equals(t.getEndTime()));
            if (partial) {
                ResourceTimeslot conflict = overlapping.stream().filter(t -> !dto.getStartTime().equals(t.getStartTime())
                        || !dto.getEndTime().equals(t.getEndTime())).findFirst().orElseThrow();
                throw new BusinessException(ErrorCode.TIMESLOT_CONFLICT, "周" + day + "与已有时段"
                        + conflict.getStartTime() + "～" + conflict.getEndTime() + "重叠，本批未新增");
            }
            if (overlapping.isEmpty()) created.add(day); else skipped.add(day);
        }
        for (Integer day : created) {
            ResourceTimeslot row = new ResourceTimeslot();
            row.setResourceId(resourceId); row.setCommunityId(resource.getCommunityId()); row.setDayOfWeek(day);
            row.setStartTime(dto.getStartTime()); row.setEndTime(dto.getEndTime());
            row.setIsAvailable(dto.getIsAvailable() == null ? 1 : dto.getIsAvailable());
            timeslotMapper.insert(row);
        }
        return new com.community.residence.common.community.vo.BatchTimeslotVO(created, skipped);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "RESOURCE_TIMESLOT", targetId = "#id", content = "'更新资源时段'")
    public TimeSlotVO update(Long id, CreateTimeSlotDTO dto) {
        ResourceTimeslot timeslot = requireTimeslot(id);
        SecurityUtils.checkCommunityAccess(timeslot.getCommunityId());
        validateTimeRange(dto);
        PublicResource resource = publicResourceService.requireResourceLocked(timeslot.getResourceId());
        timeslot = requireTimeslotCurrent(id);
        checkSlotAlignment(resource, dto);
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
    @com.community.residence.common.log.annotation.OperationLog(operationType = "DELETE", targetType = "RESOURCE_TIMESLOT", targetId = "#id")
    public void delete(Long id) {
        ResourceTimeslot timeslot = requireTimeslot(id);
        SecurityUtils.checkCommunityAccess(timeslot.getCommunityId());
        publicResourceService.requireResourceLocked(timeslot.getResourceId());
        timeslot = requireTimeslotCurrent(id);
        /* R6 删除保护（DEF-015）：预约表无 timeslot_id，按资源+起止时段匹配引用；
           周循环模板按 (resource_id, day_of_week, start_time, end_time) 定位，
           存在该模板形状的占用中预约（任意日期）即拒绝删除 */
        Long referencing = reservationMapper.selectCount(
                new LambdaQueryWrapper<com.community.residence.common.reservation.entity.ResourceReservation>()
                        .eq(com.community.residence.common.reservation.entity.ResourceReservation::getResourceId,
                                timeslot.getResourceId())
                        .eq(com.community.residence.common.reservation.entity.ResourceReservation::getStartTime,
                                timeslot.getStartTime())
                        .eq(com.community.residence.common.reservation.entity.ResourceReservation::getEndTime,
                                timeslot.getEndTime())
                        .in(com.community.residence.common.reservation.entity.ResourceReservation::getStatus,
                                java.util.List.of("PENDING", "RESERVED")));
        if (referencing > 0) {
            throw new BusinessException(ErrorCode.TIMESLOT_HAS_RESERVATION);
        }
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

    private ResourceTimeslot requireTimeslotCurrent(Long id) {
        ResourceTimeslot row = timeslotMapper.selectOne(new LambdaQueryWrapper<ResourceTimeslot>().eq(ResourceTimeslot::getId,id).last("FOR UPDATE"));
        if(row==null)throw new ResourceNotFoundException("时段不存在或已删除");
        return row;
    }

    private void validateTimeRange(CreateTimeSlotDTO dto) {
        if (!dto.getStartTime().isBefore(dto.getEndTime())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "开始时间必须早于结束时间");
        }
    }

    /* Slot Grid 模板对齐校验（08 §3.7）：起止须为资源 slot_unit 整数倍，
       源头保栅格纯净；存量非对齐模板保留（可约计算宽容分桶），仅拦新写 */
    private void checkSlotAlignment(PublicResource resource, CreateTimeSlotDTO dto) {
        int slotUnit = com.community.residence.common.reservation.service.SlotGrids.slotUnitOf(resource);
        if (!com.community.residence.common.reservation.service.SlotGrids.isAligned(dto.getStartTime(), slotUnit)
                || !com.community.residence.common.reservation.service.SlotGrids.isAligned(dto.getEndTime(), slotUnit)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "时段起止必须为预约最小单位（" + slotUnit + " 分钟）的整数倍");
        }
    }

    /* 同资源同星期时段重叠检测：排除自身（更新场景）；半开区间 [start, end) 判定 */
    private void checkOverlap(Long resourceId, Integer dayOfWeek,
                              java.time.LocalTime startTime, java.time.LocalTime endTime, Long excludeId) {
        List<ResourceTimeslot> existing = timeslotMapper.selectList(
                new LambdaQueryWrapper<ResourceTimeslot>()
                        .eq(ResourceTimeslot::getResourceId, resourceId)
                        .eq(ResourceTimeslot::getDayOfWeek, dayOfWeek).last("FOR UPDATE"));
        boolean overlapped = existing.stream()
                .anyMatch(t -> !t.getId().equals(excludeId)
                        && startTime.isBefore(t.getEndTime())
                        && t.getStartTime().isBefore(endTime));
        if (overlapped) {
            throw new BusinessException(ErrorCode.TIMESLOT_CONFLICT);
        }
    }
}
