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
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.housing.dto.BatchGenerateHousingDTO;
import com.community.residence.housing.dto.CreateHousingDTO;
import com.community.residence.housing.dto.UpdateHousingStatusDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.BatchGenerateResultVO;
import com.community.residence.housing.vo.BuildingHousingTreeVO;
import com.community.residence.housing.vo.HouseManageItemVO;
import com.community.residence.housing.vo.HousingBriefVO;
import com.community.residence.housing.vo.HousingSummaryVO;
import com.community.residence.housing.vo.HousingVO;
import com.community.residence.housing.vo.HouseHousingTreeVO;
import com.community.residence.housing.vo.UnitHousingTreeVO;
import com.community.residence.reservation.service.SlotGrids;
import com.community.residence.reservation.vo.AvailableSlotVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** 看房切片粒度（分钟，DEF-061：V6 模板为 3~4 小时长段，整段暴露导致只能约整段
        且超 R60 连续上限，对齐 C7 Slot Grid 口径栅格化；housing_timeslot 无 slot_unit 列，
        粒度固定 60 分钟） */
    private static final int VIEWING_SLOT_MINUTES = 60;

    /** 房屋管理列表挂牌筛选三态（接口设计.md 9.15.3）；缺省 all */
    private static final Set<String> LISTING_MODES = Set.of("all", "listed", "unlisted");

    /** 已挂牌判定子查询（外层主表为 house）：存在 housing 记录即视为已挂牌 */
    private static final String EXISTS_LISTED_HOUSING_SQL =
            "SELECT 1 FROM housing h WHERE h.house_id = house.id";

    /** 批量挂牌时段来源（接口设计.md 9.15.1）；缺省 DEFAULT */
    private static final Set<String> TIMESLOT_MODES = Set.of("DEFAULT", "CUSTOM");

    /** 批量挂牌默认看房时段（用户口径：默认勾选，工作日 09:00-12:00 与 14:00-18:00 两段） */
    private static final List<TimeslotSpec> DEFAULT_TIMESLOTS = buildDefaultTimeslots();

    private static List<TimeslotSpec> buildDefaultTimeslots() {
        List<TimeslotSpec> specs = new ArrayList<>();
        for (int dayOfWeek = 1; dayOfWeek <= 5; dayOfWeek++) {
            specs.add(new TimeslotSpec(dayOfWeek, LocalTime.of(9, 0), LocalTime.of(12, 0)));
            specs.add(new TimeslotSpec(dayOfWeek, LocalTime.of(14, 0), LocalTime.of(18, 0)));
        }
        return List.copyOf(specs);
    }

    private final HousingMapper housingMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final CommunityMapper communityMapper;
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
        return toVO(housing, pendingViewDelta(id));
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
        /* 未回写增量按页内 id 集合 multiGet 一次取回（N2：不做逐行查询） */
        Map<Long, Long> viewDeltas = pendingViewDeltas(result.getRecords());
        return PageVO.of(result.convert(h -> toVO(h, viewDeltas.get(h.getId()))));
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

    /* 记录浏览：INCR Redis 计数器（架构设计.md §3.3.1），定时任务每 5 分钟回写 view_count；
       降级：Redis 不可用时直接累加数据库，保证计数不丢。
       返回计入本次后的最新浏览数（展示口径 = DB 值 + 未回写 Redis 增量），
       供前端进入详情页后即时反映实时计数（不必等回写任务的一个周期） */
    @Transactional(rollbackFor = Exception.class)
    public Long recordView(Long id) {
        Housing housing = requireHousing(id);
        try {
            Long increment = redisTemplate.opsForValue().increment(VIEW_COUNT_KEY_PREFIX + id);
            return (long) effectiveViewCount(housing.getViewCount(), increment);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis 不可用，浏览计数直写数据库：housingId={}", id);
            housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                    .eq(Housing::getId, id)
                    .setSql("view_count = view_count + 1"));
            /* 直写分支回读数据库新值（并发累加下回读比本地 +1 更准；回读失败退回 +1） */
            Housing latest = housingMapper.selectById(id);
            return latest != null
                    ? (long) effectiveViewCount(latest.getViewCount(), null)
                    : (long) effectiveViewCount(housing.getViewCount(), 1L);
        }
    }

    /**
     * 展示口径 = DB 值 + 未回写 Redis 增量（回写任务 5 分钟周期，故 DB 值可能滞后至多
     * 一个周期；此处把增量并回展示值，避免任意端进入详情/列表看到滞后计数）。
     * 增量缺失或非法按 0 计；结果非负且按 Integer 上限封顶，避免溢出为负。
     */
    private int effectiveViewCount(Integer dbCount, Long pendingDelta) {
        long total = (dbCount != null ? dbCount : 0L) + (pendingDelta != null ? pendingDelta : 0L);
        return (int) Math.min(Math.max(total, 0L), Integer.MAX_VALUE);
    }

    /** 单房源未回写增量（详情单键 get）；Redis 异常回落 null（只读路径不抛错，按 DB 值展示） */
    private Long pendingViewDelta(Long housingId) {
        try {
            return parseViewDelta(redisTemplate.opsForValue().get(VIEW_COUNT_KEY_PREFIX + housingId));
        } catch (Exception e) {
            log.warn("读取浏览计数增量失败，展示回落数据库值：housingId={}", housingId, e);
            return null;
        }
    }

    /** 批量版未回写增量（列表按页内 id 集合 multiGet 批量取，避免逐行查询）；异常回落空表 */
    private Map<Long, Long> pendingViewDeltas(List<Housing> housings) {
        if (housings == null || housings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = housings.stream().map(Housing::getId)
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> deltas = new HashMap<>();
        try {
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(ids.stream().map(id -> VIEW_COUNT_KEY_PREFIX + id).toList());
            if (values == null) {
                return Map.of();
            }
            for (int i = 0; i < ids.size() && i < values.size(); i++) {
                Long delta = parseViewDelta(values.get(i));
                if (delta != null && delta != 0L) {
                    deltas.put(ids.get(i), delta);
                }
            }
        } catch (Exception e) {
            log.warn("批量读取浏览计数增量失败，展示回落数据库值：count={}", ids.size(), e);
            return Map.of();
        }
        return deltas;
    }

    /** 增量文本解析：null/空白/非法一律按 0 计（键值异常不阻断展示路径） */
    private Long parseViewDelta(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            log.warn("浏览计数键值非法，展示按 0 计：value={}", text);
            return null;
        }
    }

    /**
     * 看房可预约时段（接口设计 9.12.2.8，BE-ISSUE-5 + DEF-061）：周模板按日期范围展开 +
     * 60 分钟栅格切片（对齐 C7 Slot Grid 口径）——模板段 [start,end) 切为
     * [start,start+60m)、[start+60m,start+120m)…，尾段不足 60 分钟不产出切片；
     * 占用按「与切片时间区间重叠」计数（覆盖即占用，看房 capacity=1：一条跨两片的
     * 预约使两片均满）；maxBookings 固定 1（与创建口径一致）；timeslotId 为
     * 「模板ID×10000+当日分钟数」的栅格标识（前端选择键，非数据库主键）。
     * endDate 缺省展开 7 天；占用状态与创建冲突检测同口径（TO_CONFIRM/RESERVED）。
     * DEF-045：今日已结束切片（end<=now）不返回，与创建侧过去时段校验同口径。
     * DEF-062：切片步进按模板跨度分钟数推进，起点跨 24:00 不再死循环（详见切片循环注释）。
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
                        .in(ViewingAppointment::getStatus, ACTIVE_APPOINTMENT_STATUS)
                        .ge(ViewingAppointment::getAppointmentDate, startDate)
                        .le(ViewingAppointment::getAppointmentDate, end));

        /* DEF-045：今日已结束的切片不再返回（end<=now 剔除而非标 FULL——
           用户预期是"不能预约已过去的时段"即不展示）；明日及以后不受影响 */
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        List<AvailableSlotVO> slots = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(end); d = d.plusDays(1)) {
            final LocalDate date = d;
            int dayOfWeek = date.getDayOfWeek().getValue();
            List<ViewingAppointment> dayOccupying = occupying.stream()
                    /* 状态过滤与查询条件同口径（防御性复核，保持 BE-ISSUE-5 行为） */
                    .filter(a -> ACTIVE_APPOINTMENT_STATUS.contains(a.getStatus()))
                    .filter(a -> a.getAppointmentDate().equals(date)).toList();
            for (HousingTimeslot template : templates) {
                if (template.getDayOfWeek() != dayOfWeek) {
                    continue;
                }
                /* DEF-061 切片：自模板起点起按 60 分钟步长产出切片，切片终点超出模板
                   终点即尾段不足 60 分钟，丢弃不产出。
                   DEF-062：步进以「模板跨度内的分钟偏移」推进，不用 LocalTime 时刻比较
                   作终止条件——切片起点 +60 分钟跨过 24:00 会绕回当天早间（如 23:17
                   →00:17），`!isAfter(end)` 永不为真 → 死循环狂塞切片直至 OOM
                   （2026-09-20 实测：模板 22:17-23:17 在 21:47 触发）。 */
                long templateMinutes = Duration.between(
                        template.getStartTime(), template.getEndTime()).toMinutes();
                for (long offset = 0;
                        offset + VIEWING_SLOT_MINUTES <= templateMinutes;
                        offset += VIEWING_SLOT_MINUTES) {
                    LocalTime slotStart = template.getStartTime().plusMinutes(offset);
                    LocalTime slotEnd = slotStart.plusMinutes(VIEWING_SLOT_MINUTES);
                    if (date.isEqual(today) && !slotEnd.isAfter(now)) {
                        continue;
                    }
                    /* 重叠计数：预约区间与切片区间有交集（半开区间 start<slotEnd && end>slotStart）
                       即计入该切片占用，跨片预约使其覆盖的每片均计 1 */
                    int current = (int) dayOccupying.stream()
                            .filter(a -> a.getStartTime().isBefore(slotEnd)
                                    && a.getEndTime().isAfter(slotStart))
                            .count();
                    slots.add(new AvailableSlotVO(
                            SlotGrids.gridId(template.getId(), slotStart), date,
                            slotStart, slotEnd, 1, current,
                            current >= 1 ? "FULL" : "AVAILABLE"));
                }
            }
        }
        /* 同日按开始时间稳定排序（对齐 C7 availableSlots 口径） */
        slots.sort(java.util.Comparator.comparing(AvailableSlotVO::getDate)
                .thenComparing(AvailableSlotVO::getStartTime));
        return slots;
    }

    /**
     * 按（社区/楼栋/单元/房屋）批量挂牌（R62）：为**无在架房源**的房屋按默认参数批量
     * 生成 AVAILABLE 房源（title=「{楼栋}{单元}{房号}·精装房源[·后缀]」、月租/押金/
     * 租售类型参数化、户型冗余自 house.layout）；幂等口径与单套挂牌一致——存在非
     * OFFLINE 房源的房屋跳过（仅 OFFLINE 下架记录的房屋可重新生成在架房源）。
     * 粒度收窄链：houseIds > unitIds > buildingIds > 整个社区（叠加时逐级过滤取交集）；
     * 越权/跨社区 ID 一律拒绝。ADMIN 限绑定社区（checkCommunityAccess）。
     * 可同时为**本次新建**房源建立可预约看房时段（createTimeslots 缺省 true，
     * timeslotMode 缺省 DEFAULT=周一至周五 09:00-12:00 + 14:00-18:00）；跳过的房屋不建时段。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "HOUSING",
            targetId = "#dto.communityId", content = "'批量挂牌：' + #dto.communityId")
    public BatchGenerateResultVO batchGenerate(BatchGenerateHousingDTO dto) {
        communityService.requireCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        /* 时段参数先校验（越界 400 早失败，不落任何房源） */
        List<TimeslotSpec> timeslotSpecs = resolveTimeslotSpecs(dto);

        List<House> houses = houseMapper.selectList(new LambdaQueryWrapper<House>()
                .eq(House::getCommunityId, dto.getCommunityId())
                .orderByAsc(House::getId));

        /* 细粒度收窄：楼栋/单元/房屋三级过滤（逐级取交集）；跨社区 ID 拒绝 */
        Set<Long> allowedUnitIds = null;
        if (dto.getBuildingIds() != null && !dto.getBuildingIds().isEmpty()) {
            Set<Long> found = buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                            .eq(Building::getCommunityId, dto.getCommunityId())
                            .in(Building::getId, dto.getBuildingIds()))
                    .stream().map(Building::getId).collect(java.util.stream.Collectors.toSet());
            if (!found.containsAll(dto.getBuildingIds())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "存在不属于该社区的楼栋");
            }
            allowedUnitIds = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                            .in(Unit::getBuildingId, dto.getBuildingIds()))
                    .stream().map(Unit::getId).collect(java.util.stream.Collectors.toSet());
        }
        if (dto.getUnitIds() != null && !dto.getUnitIds().isEmpty()) {
            Set<Long> found = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                            .eq(Unit::getCommunityId, dto.getCommunityId())
                            .in(Unit::getId, dto.getUnitIds()))
                    .stream().map(Unit::getId).collect(java.util.stream.Collectors.toSet());
            if (!found.containsAll(dto.getUnitIds())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "存在不属于该社区的单元");
            }
            /* 过滤集取请求集本身（已校验全部属于该社区），不依赖查询返回集的范围 */
            Set<Long> requested = new java.util.HashSet<>(dto.getUnitIds());
            allowedUnitIds = allowedUnitIds == null ? requested : intersect(allowedUnitIds, requested);
        }
        Set<Long> allowedHouseIds = null;
        if (dto.getHouseIds() != null && !dto.getHouseIds().isEmpty()) {
            Set<Long> found = houseMapper.selectList(new LambdaQueryWrapper<House>()
                            .eq(House::getCommunityId, dto.getCommunityId())
                            .in(House::getId, dto.getHouseIds()))
                    .stream().map(House::getId).collect(java.util.stream.Collectors.toSet());
            if (!found.containsAll(dto.getHouseIds())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "存在不属于该社区的房屋");
            }
            allowedHouseIds = new java.util.HashSet<>(dto.getHouseIds());
        }
        if (allowedUnitIds != null) {
            Set<Long> unitFilter = allowedUnitIds;
            houses = houses.stream().filter(h -> unitFilter.contains(h.getUnitId())).toList();
        }
        if (allowedHouseIds != null) {
            Set<Long> houseFilter = allowedHouseIds;
            houses = houses.stream().filter(h -> houseFilter.contains(h.getId())).toList();
        }
        if (houses.isEmpty()) {
            return BatchGenerateResultVO.of(0, 0, 0);
        }
        /* 在架房源房屋集（非 OFFLINE，与 create 唯一性口径一致） */
        List<Long> houseIds = houses.stream().map(House::getId).toList();
        Set<Long> listedHouseIds = housingMapper.selectList(new LambdaQueryWrapper<Housing>()
                        .in(Housing::getHouseId, houseIds)
                        .ne(Housing::getStatus, "OFFLINE"))
                .stream().map(Housing::getHouseId).collect(java.util.stream.Collectors.toSet());

        /* 标题需要 楼栋/单元 名称：批量装载本社区结构映射（避免逐房查询） */
        Map<Long, Unit> unitsById = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                        .eq(Unit::getCommunityId, dto.getCommunityId()))
                .stream().collect(java.util.stream.Collectors.toMap(Unit::getId, u -> u));
        Map<Long, String> buildingNames = buildingMapper.selectList(
                        new LambdaQueryWrapper<Building>()
                                .eq(Building::getCommunityId, dto.getCommunityId()))
                .stream().collect(java.util.stream.Collectors.toMap(Building::getId, Building::getName));
        String titleSuffix = StringUtils.hasText(dto.getTitleSuffix())
                ? "·" + dto.getTitleSuffix().trim() : "";

        int created = 0;
        List<Long> createdHousingIds = new ArrayList<>();
        for (House house : houses) {
            if (listedHouseIds.contains(house.getId())) {
                continue;
            }
            Unit unit = unitsById.get(house.getUnitId());
            String buildingName = unit != null
                    ? buildingNames.getOrDefault(unit.getBuildingId(), "") : "";
            String unitName = unit != null ? unit.getName() : "";
            Housing housing = new Housing();
            housing.setCommunityId(dto.getCommunityId());
            housing.setHouseId(house.getId());
            housing.setTitle(buildingName + unitName + house.getHouseNumber() + "·精装房源" + titleSuffix);
            housing.setMonthlyRent(dto.getMonthlyRent());
            housing.setDeposit(dto.getDeposit());
            housing.setRentType(StringUtils.hasText(dto.getRentType()) ? dto.getRentType() : "RENT");
            housing.setLayout(house.getLayout());
            housing.setStatus("AVAILABLE");
            housing.setViewCount(0);
            housing.setPublishTime(LocalDateTime.now());
            housingMapper.insert(housing);
            createdHousingIds.add(housing.getId());
            created++;
        }
        /* 仅本次新建房源建时段：跳过的房屋（已有在架房源）不动其既有时段配置 */
        int timeslotsCreated = createTimeslotRows(createdHousingIds, timeslotSpecs);
        int skipped = houses.size() - created;
        log.info("批量挂牌完成：communityId={}, buildings={}, units={}, houses={}, created={}, skipped={}, "
                        + "timeslotsCreated={}, operator={}",
                dto.getCommunityId(), dto.getBuildingIds(), dto.getUnitIds(), dto.getHouseIds(),
                created, skipped, timeslotsCreated, SecurityUtils.getUserId());
        return BatchGenerateResultVO.of(created, skipped, timeslotsCreated);
    }

    /**
     * 解析批量挂牌要建立的看房时段：createTimeslots 缺省视为 true（默认勾选），
     * timeslotMode 缺省 DEFAULT（工作日 09:00-12:00 + 14:00-18:00）；
     * CUSTOM 须给出非空 timeslots，且 dayOfWeek 1~7、start<end，越界一律 400。
     * 显式 createTimeslots=false 时返回空表（此时不校验时段列表内容）。
     */
    private List<TimeslotSpec> resolveTimeslotSpecs(BatchGenerateHousingDTO dto) {
        String mode = StringUtils.hasText(dto.getTimeslotMode())
                ? dto.getTimeslotMode().trim().toUpperCase() : "DEFAULT";
        if (!TIMESLOT_MODES.contains(mode)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "时段来源仅支持 DEFAULT/CUSTOM");
        }
        if (Boolean.FALSE.equals(dto.getCreateTimeslots())) {
            return List.of();
        }
        if ("DEFAULT".equals(mode)) {
            return DEFAULT_TIMESLOTS;
        }
        List<BatchGenerateHousingDTO.TimeslotItem> items = dto.getTimeslots();
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "自定义时段不能为空");
        }
        List<TimeslotSpec> specs = new ArrayList<>(items.size());
        for (BatchGenerateHousingDTO.TimeslotItem item : items) {
            if (item == null || item.getDayOfWeek() == null
                    || item.getDayOfWeek() < 1 || item.getDayOfWeek() > 7) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "星期几取值 1~7");
            }
            if (item.getStartTime() == null || item.getEndTime() == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "开始时间与结束时间不能为空");
            }
            if (!item.getStartTime().isBefore(item.getEndTime())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "开始时间必须早于结束时间");
            }
            specs.add(new TimeslotSpec(item.getDayOfWeek(), item.getStartTime(), item.getEndTime()));
        }
        return specs;
    }

    /**
     * 为本次新建房源批量建立可预约看房时段（is_available=1）。
     * 幂等：同房源 + 同星期 + 同起止时间已存在则跳过（重复执行不堆叠时段）；
     * 既有记录一次查询取回（按 housingId IN，避免逐房查询），本批次内新建的键同步入集合。
     */
    private int createTimeslotRows(List<Long> housingIds, List<TimeslotSpec> specs) {
        if (specs.isEmpty() || housingIds == null || housingIds.isEmpty()) {
            return 0;
        }
        List<Long> targetHousingIds = housingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (targetHousingIds.isEmpty()) {
            return 0;
        }
        Set<String> existingKeys = new java.util.HashSet<>();
        for (HousingTimeslot existing : timeslotMapper.selectList(new LambdaQueryWrapper<HousingTimeslot>()
                .in(HousingTimeslot::getHousingId, targetHousingIds))) {
            existingKeys.add(timeslotKey(existing.getHousingId(), existing.getDayOfWeek(),
                    existing.getStartTime(), existing.getEndTime()));
        }
        int created = 0;
        for (Long housingId : targetHousingIds) {
            for (TimeslotSpec spec : specs) {
                if (!existingKeys.add(timeslotKey(housingId, spec.dayOfWeek(),
                        spec.startTime(), spec.endTime()))) {
                    continue;
                }
                HousingTimeslot timeslot = new HousingTimeslot();
                timeslot.setHousingId(housingId);
                timeslot.setDayOfWeek(spec.dayOfWeek());
                timeslot.setStartTime(spec.startTime());
                timeslot.setEndTime(spec.endTime());
                timeslot.setIsAvailable(1);
                timeslotMapper.insert(timeslot);
                created++;
            }
        }
        return created;
    }

    /** 时段去重键（房源 + 星期 + 起止）：与 housing_timeslot 唯一性口径一致 */
    private String timeslotKey(Long housingId, Integer dayOfWeek, LocalTime startTime, LocalTime endTime) {
        return housingId + "|" + dayOfWeek + "|" + startTime + "|" + endTime;
    }

    /** 批量挂牌时段项（内部值对象，避免直接落 DTO） */
    private record TimeslotSpec(Integer dayOfWeek, LocalTime startTime, LocalTime endTime) {
    }

    private static Set<Long> intersect(Set<Long> a, Set<Long> b) {
        Set<Long> result = new java.util.HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    public Housing requireHousing(Long id) {
        Housing housing = housingMapper.selectById(id);
        if (housing == null) {
            throw new ResourceNotFoundException("房源不存在");
        }
        return housing;
    }

    /**
     * 房源社区一体化树（R62）：楼栋→单元→房屋层级，房屋节点内嵌房源摘要
     * （未挂牌为 null）。ADMIN 限绑定社区（checkCommunityAccess）；层级按
     * id 升序稳定输出。每房屋取**最新一条**房源（id 最大；一房多挂牌仅在
     * 历史下架场景出现，最新记录反映当前挂牌状态）。
     */
    public List<BuildingHousingTreeVO> housesWithHousing(Long communityId) {
        communityService.requireCommunity(communityId);
        SecurityUtils.checkCommunityAccess(communityId);

        List<Building> buildings = buildingMapper.selectList(
                new LambdaQueryWrapper<Building>()
                        .eq(Building::getCommunityId, communityId)
                        .orderByAsc(Building::getId));
        List<Unit> units = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .eq(Unit::getCommunityId, communityId)
                .orderByAsc(Unit::getId));
        List<House> houses = houseMapper.selectList(new LambdaQueryWrapper<House>()
                .eq(House::getCommunityId, communityId)
                .orderByAsc(House::getId));
        List<Housing> housings = housingMapper.selectList(new LambdaQueryWrapper<Housing>()
                .eq(Housing::getCommunityId, communityId)
                .orderByAsc(Housing::getId));

        /* 房屋 → 最新房源（id 最大者，循环内覆盖实现） */
        Map<Long, Housing> latestHousingByHouse = new HashMap<>();
        for (Housing housing : housings) {
            latestHousingByHouse.put(housing.getHouseId(), housing);
        }
        /* 单元 → 房屋分组（保持 id 升序，groupBy linked 保持插入序） */
        Map<Long, List<House>> housesByUnit = new HashMap<>();
        for (House house : houses) {
            housesByUnit.computeIfAbsent(house.getUnitId(), k -> new ArrayList<>()).add(house);
        }
        Map<Long, List<Unit>> unitsByBuilding = new HashMap<>();
        for (Unit unit : units) {
            unitsByBuilding.computeIfAbsent(unit.getBuildingId(), k -> new ArrayList<>()).add(unit);
        }

        List<BuildingHousingTreeVO> tree = new ArrayList<>(buildings.size());
        for (Building building : buildings) {
            BuildingHousingTreeVO buildingVO = new BuildingHousingTreeVO();
            buildingVO.setId(building.getId());
            buildingVO.setName(building.getName());
            List<UnitHousingTreeVO> unitVOs = new ArrayList<>();
            for (Unit unit : unitsByBuilding.getOrDefault(building.getId(), List.of())) {
                UnitHousingTreeVO unitVO = new UnitHousingTreeVO();
                unitVO.setId(unit.getId());
                unitVO.setName(unit.getName());
                List<HouseHousingTreeVO> houseVOs = new ArrayList<>();
                for (House house : housesByUnit.getOrDefault(unit.getId(), List.of())) {
                    HouseHousingTreeVO houseVO = new HouseHousingTreeVO();
                    houseVO.setId(house.getId());
                    houseVO.setHouseNumber(house.getHouseNumber());
                    houseVO.setFloor(house.getFloor());
                    houseVO.setArea(house.getArea());
                    houseVO.setLayout(house.getLayout());
                    Housing housing = latestHousingByHouse.get(house.getId());
                    houseVO.setHousing(housing != null ? HousingSummaryVO.from(housing) : null);
                    houseVOs.add(houseVO);
                }
                unitVO.setHouses(houseVOs);
                unitVOs.add(unitVO);
            }
            buildingVO.setUnits(unitVOs);
            tree.add(buildingVO);
        }
        return tree;
    }

    /**
     * 房屋管理分页列表（接口设计.md 9.15.3）：房屋为主线，挂牌信息为可选子对象
     * （未挂牌 housing 为 null），供管理端「房屋与房源」带图卡片 + 筛选布局使用。
     * 过滤：社区（空=全部管辖社区）/楼栋/单元/房号关键字 + 挂牌三态
     * （all 全部，listed 存在 housing 记录，unlisted 无 housing 记录——NOT EXISTS
     * 子查询随分页下推到 SQL，不做页内内存过滤，total 保持全集口径）。
     * 稳定分页排序：社区 → 单元 → 房号 升序。
     * 数据范围：house 表含 community_id 且不在 DataScopeInterceptor 跳过名单，
     * ADMIN 绑定社区过滤由拦截器在 SQL 层注入（未绑定传越权 communityId 得空页）。
     */
    public PageVO<HouseManageItemVO> pageHouseManage(Long communityId, Long buildingId, Long unitId,
                                                     String keyword, String listing, long page, long size) {
        String listingMode = StringUtils.hasText(listing) ? listing.trim().toLowerCase() : "all";
        if (!LISTING_MODES.contains(listingMode)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "挂牌状态仅支持 all/listed/unlisted");
        }
        long pageSize = Math.min(size, 100);
        String houseNumberKeyword = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<House> wrapper = new LambdaQueryWrapper<House>()
                .eq(communityId != null, House::getCommunityId, communityId)
                .eq(unitId != null, House::getUnitId, unitId)
                .like(houseNumberKeyword != null, House::getHouseNumber, houseNumberKeyword)
                .orderByAsc(House::getCommunityId)
                .orderByAsc(House::getUnitId)
                .orderByAsc(House::getHouseNumber);
        if (buildingId != null) {
            /* house 无楼栋外键：先把楼栋下单元集合解析出来再收窄（单条查询，非逐行） */
            List<Long> buildingUnitIds = unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                            .eq(Unit::getBuildingId, buildingId))
                    .stream().map(Unit::getId).toList();
            if (buildingUnitIds.isEmpty()) {
                return PageVO.of(List.of(), 0, page, pageSize);
            }
            wrapper.in(House::getUnitId, buildingUnitIds);
        }
        if ("listed".equals(listingMode)) {
            wrapper.exists(EXISTS_LISTED_HOUSING_SQL);
        } else if ("unlisted".equals(listingMode)) {
            wrapper.notExists(EXISTS_LISTED_HOUSING_SQL);
        }
        Page<House> result = houseMapper.selectPage(new Page<>(page, pageSize), wrapper);
        return PageVO.of(assembleManageItems(result.getRecords()), result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    /**
     * 房屋管理列表装配：单元/楼栋/社区名称与挂牌房源均按主键批量取回（各一条查询，
     * 无逐行查询即无 N+1）；同一房屋多条挂牌记录取 id 最大者（与一体化树同口径）。
     */
    private List<HouseManageItemVO> assembleManageItems(List<House> houses) {
        if (houses.isEmpty()) {
            return List.of();
        }
        Map<Long, Unit> unitsById = new HashMap<>();
        for (Unit unit : unitMapper.selectList(new LambdaQueryWrapper<Unit>()
                .in(Unit::getId, houses.stream().map(House::getUnitId).distinct().toList()))) {
            unitsById.put(unit.getId(), unit);
        }
        Map<Long, String> buildingNames = new HashMap<>();
        List<Long> buildingIds = unitsById.values().stream()
                .map(Unit::getBuildingId).filter(Objects::nonNull).distinct().toList();
        if (!buildingIds.isEmpty()) {
            for (Building building : buildingMapper.selectList(new LambdaQueryWrapper<Building>()
                    .in(Building::getId, buildingIds))) {
                buildingNames.put(building.getId(), building.getName());
            }
        }
        Map<Long, String> communityNames = new HashMap<>();
        for (Community community : communityMapper.selectList(new LambdaQueryWrapper<Community>()
                .in(Community::getId, houses.stream().map(House::getCommunityId).distinct().toList()))) {
            communityNames.put(community.getId(), community.getName());
        }
        Map<Long, Housing> latestHousingByHouse = new HashMap<>();
        for (Housing housing : housingMapper.selectList(new LambdaQueryWrapper<Housing>()
                .in(Housing::getHouseId, houses.stream().map(House::getId).toList())
                .orderByAsc(Housing::getId))) {
            latestHousingByHouse.put(housing.getHouseId(), housing);
        }

        List<HouseManageItemVO> items = new ArrayList<>(houses.size());
        for (House house : houses) {
            HouseManageItemVO item = new HouseManageItemVO();
            item.setHouseId(house.getId());
            item.setCommunityId(house.getCommunityId());
            item.setCommunityName(communityNames.get(house.getCommunityId()));
            item.setUnitId(house.getUnitId());
            Unit unit = unitsById.get(house.getUnitId());
            if (unit != null) {
                item.setUnitName(unit.getName());
                item.setBuildingId(unit.getBuildingId());
                item.setBuildingName(buildingNames.get(unit.getBuildingId()));
            }
            item.setHouseNumber(house.getHouseNumber());
            item.setFloor(house.getFloor());
            item.setArea(house.getArea());
            item.setLayout(house.getLayout());
            item.setHouseStatus(house.getStatus());
            Housing housing = latestHousingByHouse.get(house.getId());
            item.setHousing(housing != null ? HousingBriefVO.from(housing) : null);
            items.add(item);
        }
        return items;
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
        return toVO(housing, null);
    }

    /** 详情/列表装配：pendingDelta 为未回写 Redis 增量（null=按 DB 值展示） */
    private HousingVO toVO(Housing housing, Long pendingDelta) {
        HousingVO vo = HousingVO.from(housing);
        vo.setViewCount(effectiveViewCount(housing.getViewCount(), pendingDelta));
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
