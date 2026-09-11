package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.housing.dto.CreateHousingDTO;
import com.community.residence.housing.dto.UpdateHousingStatusDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.HousingVO;
import com.community.residence.reservation.vo.AvailableSlotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 房源业务逻辑：上架管理 + 游客浏览（游客只见可租/已预订房源） */
@Slf4j
@Service
@RequiredArgsConstructor
public class HousingService {

    /** 游客可见的房源状态（已出租/下线不展示） */
    private static final List<String> PUBLIC_STATUS = List.of("AVAILABLE", "RESERVED");

    /** 未完成看房预约状态集合（删除保护判定） */
    private static final List<String> ACTIVE_APPOINTMENT_STATUS = List.of("TO_CONFIRM", "RESERVED");

    /** 浏览计数 Redis 键前缀（架构设计.md §3.3.1，HousingViewFlushTask 每 5 分钟回写） */
    public static final String VIEW_COUNT_KEY_PREFIX = "housing:view:";

    private final HousingMapper housingMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final HousingTimeslotMapper timeslotMapper;
    private final ViewingAppointmentMapper appointmentMapper;
    private final CommunityService communityService;
    private final StringRedisTemplate redisTemplate;

    /* 上架房源：社区由房屋推导；同一房屋仅允许一条非下线房源 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "HOUSING", targetId = "#result.id", content = "'创建房源：' + #dto.title")
    public HousingVO create(CreateHousingDTO dto) {
        House house = houseMapper.selectById(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        Long exists = housingMapper.selectCount(new LambdaQueryWrapper<Housing>()
                .eq(Housing::getHouseId, dto.getHouseId())
                .ne(Housing::getStatus, "OFFLINE"));
        if (exists > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "该房屋已有在架房源");
        }

        Housing housing = new Housing();
        housing.setCommunityId(house.getCommunityId());
        housing.setHouseId(house.getId());
        applyDto(housing, dto);
        /* 户型冗余自 house.layout（R53 列表筛选用，避免 join；权威值在 house） */
        housing.setLayout(house.getLayout());
        housing.setStatus("AVAILABLE");
        housing.setViewCount(0);
        housing.setPublishTime(LocalDateTime.now());
        housingMapper.insert(housing);
        return toVO(housing);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "HOUSING", targetId = "#id", content = "'更新房源：' + #dto.title")
    public HousingVO update(Long id, CreateHousingDTO dto) {
        Housing housing = requireHousing(id);
        SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        if (!housing.getHouseId().equals(dto.getHouseId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "房源不允许变更关联房屋");
        }
        applyDto(housing, dto);
        housingMapper.updateById(housing);
        return toVO(housing);
    }

    /* 删除保护：存在待确认/已预约的看房预约时拒绝；物理删除 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DELETE", targetType = "HOUSING", targetId = "#id")
    public void delete(Long id) {
        Housing housing = requireHousing(id);
        SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        Long activeCount = appointmentMapper.selectCount(new LambdaQueryWrapper<ViewingAppointment>()
                .eq(ViewingAppointment::getHousingId, id)
                .in(ViewingAppointment::getStatus, ACTIVE_APPOINTMENT_STATUS));
        if (activeCount > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "房源存在未完成看房预约，无法删除");
        }
        housingMapper.deleteById(id);
        log.info("房源已删除：housingId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /** 房源详情（公开；游客访问已出租/下线房源返回 404） */
    public HousingVO getById(Long id) {
        Housing housing = requireHousing(id);
        if (!isManager() && !PUBLIC_STATUS.contains(housing.getStatus())) {
            throw new ResourceNotFoundException("房源不存在或已下线");
        }
        return toVO(housing);
    }

    /** 房源分页列表（公开；游客默认只见可租/已预订） */
    public PageVO<HousingVO> page(long page, long size, Long communityId, String status,
                                  BigDecimal minRent, BigDecimal maxRent, String keyword,
                                  String layout, String rentType) {
        if (StringUtils.hasText(rentType) && !"RENT".equals(rentType) && !"SALE".equals(rentType)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租售类型仅支持 RENT/SALE");
        }
        LambdaQueryWrapper<Housing> wrapper = new LambdaQueryWrapper<Housing>()
                .eq(communityId != null, Housing::getCommunityId, communityId)
                .eq(StringUtils.hasText(status), Housing::getStatus, status)
                .ge(minRent != null, Housing::getMonthlyRent, minRent)
                .le(maxRent != null, Housing::getMonthlyRent, maxRent)
                /* R53 v1.2：户型精确匹配（冗余列，权威值 house.layout）+ 租售类型 */
                .eq(StringUtils.hasText(layout), Housing::getLayout, layout)
                .eq(StringUtils.hasText(rentType), Housing::getRentType, rentType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Housing::getTitle, keyword)
                        .or().like(Housing::getDescription, keyword))
                .orderByDesc(Housing::getId);
        if (!isManager() && !StringUtils.hasText(status)) {
            wrapper.in(Housing::getStatus, PUBLIC_STATUS);
        }
        Page<Housing> result = housingMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "HOUSING", targetId = "#id", content = "'房源状态变更为 ' + #dto.status")
    public void updateStatus(Long id, UpdateHousingStatusDTO dto) {
        Housing housing = requireHousing(id);
        SecurityUtils.checkCommunityAccess(housing.getCommunityId());
        housing.setStatus(dto.getStatus());
        housingMapper.updateById(housing);
        log.info("房源状态变更：housingId={}, status={}, operator={}",
                id, dto.getStatus(), SecurityUtils.getUserId());
    }

    /* 浏览计数：直接累加（高并发场景 P2 升级 Redis 计数 + 定时回写） */
    @Transactional(rollbackFor = Exception.class)
    /* 记录浏览：INCR Redis 计数器（架构设计.md §3.3.1），定时任务每 5 分钟回写 view_count；
       降级：Redis 不可用时直接累加数据库，保证计数不丢 */
    public void recordView(Long id) {
        requireHousing(id);
        try {
            redisTemplate.opsForValue().increment(VIEW_COUNT_KEY_PREFIX + id);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，浏览计数直写数据库：housingId={}", id);
            housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                    .eq(Housing::getId, id)
                    .setSql("view_count = view_count + 1"));
        }
    }

    /**
     * 看房可预约时段（接口设计 9.12.2.8，BE-ISSUE-5）：周模板按日期范围展开 +
     * 占用计数。housing_timeslot 无 max_bookings 列且预约创建按「每时段仅一条
     * 有效预约」做冲突检测，故 maxBookings 固定为 1（与创建口径一致）；
     * endDate 缺省展开 7 天；占用状态与创建冲突检测同口径（TO_CONFIRM/RESERVED）。
     */
    public List<AvailableSlotVO> availableSlots(Long housingId, LocalDate startDate, LocalDate endDate) {
        requireHousing(housingId);
        LocalDate end = endDate != null ? endDate : startDate.plusDays(6);
        List<HousingTimeslot> templates = timeslotMapper.selectList(
                new LambdaQueryWrapper<HousingTimeslot>()
                        .eq(HousingTimeslot::getHousingId, housingId)
                        .eq(HousingTimeslot::getIsAvailable, 1));
        List<ViewingAppointment> occupying = appointmentMapper.selectList(
                new LambdaQueryWrapper<ViewingAppointment>()
                        .eq(ViewingAppointment::getHousingId, housingId)
                        .in(ViewingAppointment::getStatus, "TO_CONFIRM", "RESERVED")
                        .ge(ViewingAppointment::getAppointmentDate, startDate)
                        .le(ViewingAppointment::getAppointmentDate, end));

        List<AvailableSlotVO> slots = new ArrayList<>();
        Set<String> occupyingStatus = Set.of("TO_CONFIRM", "RESERVED");
        for (LocalDate d = startDate; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate date = d;
            int dayOfWeek = date.getDayOfWeek().getValue();
            for (HousingTimeslot template : templates) {
                if (template.getDayOfWeek() != dayOfWeek) {
                    continue;
                }
                int current = (int) occupying.stream()
                        .filter(a -> occupyingStatus.contains(a.getStatus()))
                        .filter(a -> a.getAppointmentDate().equals(date)
                                && a.getStartTime().equals(template.getStartTime())
                                && a.getEndTime().equals(template.getEndTime()))
                        .count();
                slots.add(new AvailableSlotVO(template.getId(), date,
                        template.getStartTime(), template.getEndTime(),
                        1, current, current >= 1 ? "FULL" : "AVAILABLE"));
            }
        }
        return slots;
    }

    public Housing requireHousing(Long id) {
        Housing housing = housingMapper.selectById(id);
        if (housing == null) {
            throw new ResourceNotFoundException("房源不存在");
        }
        return housing;
    }

    private boolean isManager() {
        return SecurityUtils.hasRole(RoleConstants.ADMIN) || SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN);
    }

    private void applyDto(Housing housing, CreateHousingDTO dto) {
        housing.setTitle(dto.getTitle());
        housing.setDescription(dto.getDescription());
        housing.setMonthlyRent(dto.getMonthlyRent());
        housing.setDeposit(dto.getDeposit());
        housing.setRentType(StringUtils.hasText(dto.getRentType()) ? dto.getRentType() : "RENT");
        housing.setImages(dto.getImages());
    }

    private HousingVO toVO(Housing housing) {
        HousingVO vo = HousingVO.from(housing);
        var community = communityService.requireCommunity(housing.getCommunityId());
        vo.setCommunityName(community.getName());
        House house = houseMapper.selectById(housing.getHouseId());
        if (house != null) {
            Unit unit = unitMapper.selectById(house.getUnitId());
            if (unit != null) {
                Building building = buildingMapper.selectById(unit.getBuildingId());
                String buildingName = building != null ? building.getName() : "";
                vo.setHouseLocation(buildingName + unit.getName() + house.getHouseNumber());
            }
        }
        return vo;
    }
}
