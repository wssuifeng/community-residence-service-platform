package com.community.residence.housing.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.result.ApiResponse;
import com.community.residence.housing.dto.CreateHousingTimeslotDTO;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.service.HousingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 房源时段管理控制器：周循环模板维护（接口设计.md 9.12.3） */
@Tag(name = "房源时段管理", description = "房源可预约时段管理接口")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HousingTimeslotController {

    private final HousingTimeslotMapper timeslotMapper;
    private final HousingService housingService;

    @Operation(summary = "创建房源时段", description = "同房源同星期不允许重叠")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PostMapping("/housings/{housingId}/timeslots")
    public ApiResponse<HousingTimeslot> create(@PathVariable Long housingId,
                                               @RequestBody @Valid CreateHousingTimeslotDTO dto) {
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
        return ApiResponse.success(timeslot);
    }

    @Operation(summary = "更新房源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @PutMapping("/housing-timeslots/{id}")
    public ApiResponse<HousingTimeslot> update(@PathVariable Long id,
                                               @RequestBody @Valid CreateHousingTimeslotDTO dto) {
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
        return ApiResponse.success(timeslot);
    }

    @Operation(summary = "删除房源时段")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @DeleteMapping("/housing-timeslots/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        HousingTimeslot timeslot = timeslotMapper.selectById(id);
        if (timeslot != null) {
            var housing = housingService.requireHousing(timeslot.getHousingId());
            SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        }
        timeslotMapper.deleteById(id);
        return ApiResponse.success();
    }

    @Operation(summary = "房源时段列表", description = "公开；游客选择预约时段用")
    @GetMapping("/housings/{housingId}/timeslots")
    public ApiResponse<List<HousingTimeslot>> list(@PathVariable Long housingId,
                                                   @RequestParam(required = false) Integer dayOfWeek) {
        return ApiResponse.success(timeslotMapper.selectList(
                new LambdaQueryWrapper<HousingTimeslot>()
                        .eq(HousingTimeslot::getHousingId, housingId)
                        .eq(dayOfWeek != null, HousingTimeslot::getDayOfWeek, dayOfWeek)
                        .orderByAsc(HousingTimeslot::getDayOfWeek)
                        .orderByAsc(HousingTimeslot::getStartTime)));
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
