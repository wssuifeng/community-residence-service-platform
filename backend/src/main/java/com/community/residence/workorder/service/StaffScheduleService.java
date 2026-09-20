package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.constant.ShiftType;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.workorder.dto.BatchSaveScheduleDTO;
import com.community.residence.workorder.dto.ClearScheduleDTO;
import com.community.residence.workorder.entity.StaffSchedule;
import com.community.residence.workorder.mapper.StaffScheduleMapper;
import com.community.residence.workorder.vo.BatchSaveScheduleResultVO;
import com.community.residence.workorder.vo.StaffScheduleVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 服务人员排班业务逻辑（V19，C4 物业调度）：一人一社区一天一条
 * （staff_schedule.uk_staff_schedule），批量设置按「人员×日期」逐条覆盖式保存。
 * 班次起止时间以 ShiftType 默认值落库快照，管理员传自定义时间时以自定义为准，
 * REST（休息）强制清空起止时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffScheduleService {

    private static final DateTimeFormatter WORK_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 排班查询日期跨度上限（含首尾，超出报 INVALID_PARAM） */
    private static final long MAX_RANGE_DAYS = 62;

    private final StaffScheduleMapper staffScheduleMapper;
    private final SysUserMapper sysUserMapper;
    private final CommunityMapper communityMapper;

    /* 排班列表：按社区+日期范围查询（communityId 为空时不额外收敛，ADMIN 由
       数据级权限拦截器收敛到绑定社区）；排序 workDate 升序、staffId 升序。
       日期范围必填且跨度上限 62 天，防全表扫描 */
    public List<StaffScheduleVO> list(Long communityId, LocalDate startDate, LocalDate endDate, Long staffId) {
        requireDateRange(startDate, endDate, MAX_RANGE_DAYS);
        List<StaffSchedule> schedules = staffScheduleMapper.selectList(
                new LambdaQueryWrapper<StaffSchedule>()
                        .eq(communityId != null, StaffSchedule::getCommunityId, communityId)
                        .eq(staffId != null, StaffSchedule::getStaffId, staffId)
                        .between(StaffSchedule::getWorkDate, startDate, endDate)
                        .orderByAsc(StaffSchedule::getWorkDate)
                        .orderByAsc(StaffSchedule::getStaffId));
        if (schedules.isEmpty()) {
            return List.of();
        }
        return assemble(schedules);
    }

    /* 批量设置排班：人员×日期 笛卡尔积逐条落库（同社区同人同日已存在则覆盖更新）。
       社区归属与存在性、人员角色、班次取值、班次时间先后在此集中校验；
       已存在记录一次批量查出后内存匹配，避免逐条查询 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "STAFF_SCHEDULE", targetId = "#dto.communityId", communityId = "#dto.communityId", content = "'批量设置排班：' + #dto.staffIds + ' / ' + #dto.dates + ' / ' + #dto.shiftType")
    public BatchSaveScheduleResultVO batchSave(BatchSaveScheduleDTO dto) {
        if (dto == null || dto.getCommunityId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "社区不能为空");
        }
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        if (communityMapper.selectById(dto.getCommunityId()) == null) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "社区不存在：" + dto.getCommunityId());
        }
        List<Long> staffIds = distinct(dto.getStaffIds());
        if (staffIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "服务人员不能为空");
        }
        requireStaffAccounts(staffIds);
        List<LocalDate> dates = parseDates(dto.getDates());
        String shiftType = dto.getShiftType();
        if (!ShiftType.isValid(shiftType)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "班次取值须为 MORNING/AFTERNOON/EVENING/FULL/REST");
        }
        LocalTime startTime = ShiftType.REST.equals(shiftType) ? null
                : (dto.getStartTime() != null ? dto.getStartTime() : ShiftType.defaultStart(shiftType));
        LocalTime endTime = ShiftType.REST.equals(shiftType) ? null
                : (dto.getEndTime() != null ? dto.getEndTime() : ShiftType.defaultEnd(shiftType));
        if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "班次开始时间必须早于结束时间");
        }

        Map<String, StaffSchedule> existing = new HashMap<>();
        for (StaffSchedule row : staffScheduleMapper.selectList(new LambdaQueryWrapper<StaffSchedule>()
                .eq(StaffSchedule::getCommunityId, dto.getCommunityId())
                .in(StaffSchedule::getStaffId, staffIds)
                .in(StaffSchedule::getWorkDate, dates))) {
            existing.put(scheduleKey(row.getStaffId(), row.getWorkDate()), row);
        }

        int saved = 0;
        Long operator = SecurityUtils.getUserId();
        for (Long staffId : staffIds) {
            for (LocalDate date : dates) {
                StaffSchedule row = existing.get(scheduleKey(staffId, date));
                if (row == null) {
                    row = new StaffSchedule();
                    row.setStaffId(staffId);
                    row.setCommunityId(dto.getCommunityId());
                    row.setWorkDate(date);
                    row.setCreatedBy(operator);
                    fillShift(row, shiftType, startTime, endTime, dto.getRemark());
                    staffScheduleMapper.insert(row);
                } else {
                    fillShift(row, shiftType, startTime, endTime, dto.getRemark());
                    staffScheduleMapper.updateById(row);
                }
                saved++;
            }
        }
        log.info("排班已批量保存：communityId={}, staff={}人, dates={}天, shiftType={}, operator={}",
                dto.getCommunityId(), staffIds.size(), dates.size(), shiftType, operator);
        return new BatchSaveScheduleResultVO(saved);
    }

    /* 删除单条排班：写操作须显式校验社区归属（拦截器仅改写 SELECT） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        StaffSchedule schedule = id == null ? null : staffScheduleMapper.selectById(id);
        if (schedule == null) {
            throw new ResourceNotFoundException("排班记录不存在");
        }
        SecurityUtils.checkCommunityAccess(schedule.getCommunityId());
        staffScheduleMapper.deleteById(id);
    }

    /* 按社区+日期范围清空排班：staffIds 为空表示清空范围内全部人员 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DELETE", targetType = "STAFF_SCHEDULE", targetId = "#dto.communityId", communityId = "#dto.communityId", content = "'清空排班范围'")
    public BatchSaveScheduleResultVO clear(ClearScheduleDTO dto) {
        if (dto == null || dto.getCommunityId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "社区不能为空");
        }
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        requireDateRange(dto.getStartDate(), dto.getEndDate(), MAX_RANGE_DAYS);
        List<Long> staffIds = distinct(dto.getStaffIds());
        int removed = staffScheduleMapper.delete(new LambdaQueryWrapper<StaffSchedule>()
                .eq(StaffSchedule::getCommunityId, dto.getCommunityId())
                .between(StaffSchedule::getWorkDate, dto.getStartDate(), dto.getEndDate())
                .in(!staffIds.isEmpty(), StaffSchedule::getStaffId, staffIds));
        log.info("排班范围已清空：communityId={}, staffIds={}, {} ~ {}, removed={}, operator={}",
                dto.getCommunityId(), staffIds, dto.getStartDate(), dto.getEndDate(),
                removed, SecurityUtils.getUserId());
        return new BatchSaveScheduleResultVO(removed);
    }

    /** 批量取人员指定日期的班次标签（可空=当日未排班）；communityId 非空时限定该社区，
        同一人多社区同日多条时取绑定行中 id 最小的一条（一次批量查询，避免 N+1） */
    public Map<Long, String> shiftLabelsOn(Collection<Long> staffIds, Long communityId, LocalDate date) {
        Map<Long, String> labels = new LinkedHashMap<>();
        schedulesOn(staffIds, communityId, date)
                .forEach((staffId, schedule) -> labels.put(staffId, ShiftType.label(schedule.getShiftType())));
        return labels;
    }

    /** 批量取人员指定日期的班次记录（可空=当日未排班；一次批量查询，避免 N+1） */
    public Map<Long, StaffSchedule> schedulesOn(Collection<Long> staffIds, Long communityId, LocalDate date) {
        if (staffIds == null || staffIds.isEmpty() || date == null) {
            return Map.of();
        }
        List<StaffSchedule> rows = staffScheduleMapper.selectList(new LambdaQueryWrapper<StaffSchedule>()
                .in(StaffSchedule::getStaffId, staffIds)
                .eq(communityId != null, StaffSchedule::getCommunityId, communityId)
                .eq(StaffSchedule::getWorkDate, date)
                .orderByAsc(StaffSchedule::getId));
        Map<Long, StaffSchedule> result = new LinkedHashMap<>();
        for (StaffSchedule row : rows) {
            result.putIfAbsent(row.getStaffId(), row);
        }
        return result;
    }

    private void fillShift(StaffSchedule row, String shiftType, LocalTime startTime,
                           LocalTime endTime, String remark) {
        row.setShiftType(shiftType);
        row.setStartTime(startTime);
        row.setEndTime(endTime);
        row.setRemark(remark);
    }

    /* 组装排班视图：人员姓名与社区名称各一次批量查询，无逐条查询 */
    private List<StaffScheduleVO> assemble(List<StaffSchedule> schedules) {
        Set<Long> staffIds = new LinkedHashSet<>();
        Set<Long> communityIds = new LinkedHashSet<>();
        schedules.forEach(s -> {
            staffIds.add(s.getStaffId());
            communityIds.add(s.getCommunityId());
        });
        Map<Long, String> staffNames = new HashMap<>();
        for (SysUser user : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, staffIds))) {
            staffNames.put(user.getId(), user.getRealName());
        }
        Map<Long, String> communityNames = new HashMap<>();
        for (Community community : communityMapper.selectList(new LambdaQueryWrapper<Community>()
                .in(Community::getId, communityIds))) {
            communityNames.put(community.getId(), community.getName());
        }
        List<StaffScheduleVO> records = new ArrayList<>(schedules.size());
        for (StaffSchedule schedule : schedules) {
            StaffScheduleVO vo = StaffScheduleVO.from(schedule);
            vo.setStaffName(staffNames.get(schedule.getStaffId()));
            vo.setCommunityName(communityNames.get(schedule.getCommunityId()));
            records.add(vo);
        }
        return records;
    }

    /* 日期范围校验：必填、顺序合法、跨度上限（含首尾） */
    private void requireDateRange(LocalDate startDate, LocalDate endDate, long maxDays) {
        if (startDate == null || endDate == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "开始日期与结束日期不能为空");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > maxDays) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "排班日期跨度不能超过 " + maxDays + " 天");
        }
    }

    /* 批量校验人员账号（角色须为 STAFF），缺失人员一次性报出便于排查 */
    private void requireStaffAccounts(List<Long> staffIds) {
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getId, staffIds)
                .eq(SysUser::getRole, RoleConstants.STAFF));
        Set<Long> valid = users.stream().map(SysUser::getId).collect(java.util.stream.Collectors.toSet());
        List<Long> invalid = staffIds.stream().filter(id -> !valid.contains(id)).toList();
        if (!invalid.isEmpty()) {
            throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "服务人员不存在：" + invalid);
        }
    }

    private List<LocalDate> parseDates(List<String> dates) {
        if (dates == null || dates.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "排班日期不能为空");
        }
        List<LocalDate> parsed = new ArrayList<>();
        for (String date : dates) {
            if (!StringUtils.hasText(date)) {
                continue;
            }
            try {
                LocalDate value = LocalDate.parse(date.trim(), WORK_DATE);
                if (!parsed.contains(value)) {
                    parsed.add(value);
                }
            } catch (DateTimeParseException e) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "排班日期格式须为 yyyy-MM-dd：" + date);
            }
        }
        if (parsed.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "排班日期不能为空");
        }
        return parsed;
    }

    private List<Long> distinct(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private String scheduleKey(Long staffId, LocalDate date) {
        return staffId + "#" + date;
    }
}
