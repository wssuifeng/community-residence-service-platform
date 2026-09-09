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
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.HousingVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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
    private final ViewingAppointmentMapper appointmentMapper;
    private final CommunityService communityService;
    private final StringRedisTemplate redisTemplate;

    /* 上架房源：社区由房屋推导；同一房屋仅允许一条非下线房源 */
    @Transactional(rollbackFor = Exception.class)
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
        housing.setStatus("AVAILABLE");
        housing.setViewCount(0);
        housing.setPublishTime(LocalDateTime.now());
        housingMapper.insert(housing);
        return toVO(housing);
    }

    @Transactional(rollbackFor = Exception.class)
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
                                  BigDecimal minRent, BigDecimal maxRent, String keyword) {
        LambdaQueryWrapper<Housing> wrapper = new LambdaQueryWrapper<Housing>()
                .eq(communityId != null, Housing::getCommunityId, communityId)
                .eq(StringUtils.hasText(status), Housing::getStatus, status)
                .ge(minRent != null, Housing::getMonthlyRent, minRent)
                .le(maxRent != null, Housing::getMonthlyRent, maxRent)
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
