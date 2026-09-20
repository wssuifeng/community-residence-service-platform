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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * 看房可预约时段（接口设计 9.12.2.8，BE-ISSUE-5 + DEF-061）：周模板按日期范围展开 +
     * 60 分钟栅格切片（对齐 C7 Slot Grid 口径）——模板段 [start,end) 切为
     * [start,start+60m)、[start+60m,start+120m)…，尾段不足 60 分钟不产出切片；
     * 占用按「与切片时间区间重叠」计数（覆盖即占用，看房 capacity=1：一条跨两片的
     * 预约使两片均满）；maxBookings 固定 1（与创建口径一致）；timeslotId 为
     * 「模板ID×10000+当日分钟数」的栅格标识（前端选择键，非数据库主键）。
     * endDate 缺省展开 7 天；占用状态与创建冲突检测同口径（TO_CONFIRM/RESERVED）。
     * DEF-045：今日已结束切片（end<=now）不返回，与创建侧过去时段校验同口径。
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
                   终点即尾段不足 60 分钟，丢弃不产出 */
                for (LocalTime s = template.getStartTime();
                        !s.plusMinutes(VIEWING_SLOT_MINUTES).isAfter(template.getEndTime());
                        s = s.plusMinutes(VIEWING_SLOT_MINUTES)) {
                    LocalTime slotStart = s;
                    LocalTime slotEnd = s.plusMinutes(VIEWING_SLOT_MINUTES);
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
     * 按社区批量挂牌（R62）：为该社区内**无在架房源**的房屋按默认参数批量生成
     * AVAILABLE 房源（title=「{楼栋}{单元}{房号}·精装房源」、月租/押金/租售类型
     * 参数化、户型冗余自 house.layout）；幂等口径与单套挂牌一致——存在非 OFFLINE
     * 房源的房屋跳过（仅 OFFLINE 下架记录的房屋可重新生成在架房源）。
     * ADMIN 限绑定社区（checkCommunityAccess）。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "HOUSING",
            targetId = "#dto.communityId", content = "'按社区批量挂牌：' + #dto.communityId")
    public BatchGenerateResultVO batchGenerate(BatchGenerateHousingDTO dto) {
        communityService.requireCommunity(dto.getCommunityId());
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());

        List<House> houses = houseMapper.selectList(new LambdaQueryWrapper<House>()
                .eq(House::getCommunityId, dto.getCommunityId())
                .orderByAsc(House::getId));
        if (houses.isEmpty()) {
            return BatchGenerateResultVO.of(0, 0);
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

        int created = 0;
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
            housing.setTitle(buildingName + unitName + house.getHouseNumber() + "·精装房源");
            housing.setMonthlyRent(dto.getMonthlyRent());
            housing.setDeposit(dto.getDeposit());
            housing.setRentType(StringUtils.hasText(dto.getRentType()) ? dto.getRentType() : "RENT");
            housing.setLayout(house.getLayout());
            housing.setStatus("AVAILABLE");
            housing.setViewCount(0);
            housing.setPublishTime(LocalDateTime.now());
            housingMapper.insert(housing);
            created++;
        }
        int skipped = houses.size() - created;
        log.info("按社区批量挂牌完成：communityId={}, created={}, skipped={}, operator={}",
                dto.getCommunityId(), created, skipped, SecurityUtils.getUserId());
        return BatchGenerateResultVO.of(created, skipped);
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
