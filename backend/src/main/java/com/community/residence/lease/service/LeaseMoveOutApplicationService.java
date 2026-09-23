package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.MoveOutStatus;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
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
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.lease.dto.ApproveMoveOutDTO;
import com.community.residence.lease.dto.CreateMoveOutApplicationDTO;
import com.community.residence.lease.dto.RejectMoveOutDTO;
import com.community.residence.lease.entity.LeaseMoveOutApplication;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.vo.LeaseMoveOutApplicationVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 居民自助退租申请业务逻辑（R70，接口设计.md 9.19）：
 * PENDING → APPROVED / REJECTED；同一租约同时仅一条 PENDING——约束在
 * Redisson 锁内「检查 + 落库 + 提交」闭环完成（DEF-104），审核侧以
 * 「status=PENDING」条件更新抢占终态，并发双审核只生效一次。
 * 审核通过为一次跨域联动（同一事务）：租约 ACTIVE→MOVED_OUT + 居住关系写
 * moveOutDate（该租约租客在本租约房屋下未迁出的关系，无在住关系时跳过并 WARN）
 * + house.status=VACANT + 在架房源状态回收（housingStatus 缺省 AVAILABLE）
 * + lease_change_log(STATUS) + 关闭租约群聊（R68 生命周期止于租约完成）+ 通知居民；
 * 驳回仅落终态、租约保持 ACTIVE。
 * 通知深链契约（DEF-105）：type=LEASE 的 sourceId 一律传租约 ID（前端
 * notificationNav 按 /admin/leases/{id} 与 ?tab=lease&leaseId={id} 解析），
 * 三处通知均不得误传申请 ID。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseMoveOutApplicationService {

    /** 房源状态回收缺省值：退租后默认回到在架可租 */
    private static final String DEFAULT_HOUSING_STATUS = "AVAILABLE";

    private final LeaseMoveOutApplicationMapper moveOutMapper;
    private final LeaseRecordMapper leaseMapper;
    private final HouseMapper houseMapper;
    private final HousingMapper housingMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final CommunityMapper communityMapper;
    private final ResidenceRelationMapper relationMapper;
    private final ResidentMapper residentMapper;
    private final SysUserMapper sysUserMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final LeaseChangeLogService changeLogService;
    private final NotificationService notificationService;
    private final ConversationService conversationService;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    /**
     * 创建退租申请（RESIDENT 本人）：仅已生效租约、退租日期须落在租期窗口内、
     * 同租约无待审申请；落 PENDING 终态待管理方审核；提交即通知本社区管理方（退租需人工确认）。
     */
    /* DEF-104 并发防护：PENDING 唯一性是「检查后落库」型竞态（无库级唯一约束兜底），
       检查与落库须同锁完成，且承载「落库 + 通知」的事务必须在锁内提交——若保留方法级
       @Transactional，锁会在 finally 先于事务提交释放，重新打开并发窗口。故此处刻意
       不加 @Transactional（口径同 ViewingAppointmentService 预约创建），事务边界由
       TransactionTemplate 在锁内显式承载 */
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE",
            targetType = "LEASE_MOVE_OUT_APPLICATION", targetId = "#result.id",
            content = "'居民提交退租申请'")
    public LeaseMoveOutApplicationVO create(Long leaseId, CreateMoveOutApplicationDTO dto) {
        Long residentId = SecurityUtils.getUserId();
        LeaseRecord lease = requireLease(leaseId);
        if (!lease.getTenantId().equals(residentId)) {
            throw new ForbiddenException("无权对他人租约申请退租");
        }
        if (!LeaseStatus.ACTIVE.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,
                    "仅已生效租约可申请退租，当前状态：" + lease.getStatus());
        }
        /* 退租日期须落在租期窗口内（DEF-130）：早于起租日/晚于止租日的日期无业务意义
           （DTO 层 @FutureOrPresent 已挡过去日期，此处收窄到本租约租期） */
        if (dto.getMoveOutDate().isBefore(lease.getStartDate()) || dto.getMoveOutDate().isAfter(lease.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "退租日期须在租期范围内：" + lease.getStartDate() + " ~ " + lease.getEndDate());
        }

        LeaseMoveOutApplication application = new LeaseMoveOutApplication();
        application.setLeaseId(leaseId);
        application.setResidentId(residentId);
        application.setCommunityId(lease.getCommunityId());
        application.setHouseId(lease.getHouseId());
        application.setMoveOutDate(dto.getMoveOutDate());
        application.setReason(dto.getReason());
        application.setStatus(MoveOutStatus.PENDING);

        RLock lock = redissonClient.getLock("lock:moveout:" + leaseId);
        boolean locked = false;
        try {
            try {
                locked = lock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.OPERATION_FAILED, "退租申请正在处理，请稍后重试");
            }
            if (!locked) {
                throw new BusinessException(ErrorCode.OPERATION_FAILED, "退租申请正在处理，请稍后重试");
            }
            /* PENDING 唯一性检查在锁内（DEF-104）：并发双提交只放行一个 */
            Long pending = moveOutMapper.selectCount(new LambdaQueryWrapper<LeaseMoveOutApplication>()
                    .eq(LeaseMoveOutApplication::getLeaseId, leaseId)
                    .eq(LeaseMoveOutApplication::getStatus, MoveOutStatus.PENDING));
            if (pending != null && pending > 0) {
                throw new BusinessException(ErrorCode.MOVE_OUT_APPLICATION_PENDING);
            }
            /* 锁内事务提交（DEF-104）：落库与通知同事务，提交完成后才走出 finally 释放锁 */
            transactionTemplate.executeWithoutResult(status -> {
                moveOutMapper.insert(application);
                for (Long adminId : boundAdminIds(lease.getCommunityId())) {
                    notificationService.createForUser(adminId, lease.getCommunityId(),
                            "居民提交退租申请",
                            "租约 #" + leaseId + " 的居民申请于 " + dto.getMoveOutDate() + " 退租，请及时审核",
                            "LEASE", "LEASE_MOVE_OUT", application.getLeaseId());
                }
            });
            log.info("退租申请已提交：applicationId={}, leaseId={}, moveOutDate={}, residentId={}",
                    application.getId(), leaseId, dto.getMoveOutDate(), residentId);
            return toVO(application);
        } finally {
            if (locked) {
                unlockQuietly(lock);
            }
        }
    }

    /**
     * 退租申请分页（ADMIN/SUPER_ADMIN）：数据级权限由 DataScopeInterceptor
     * 按 community_id 注入（本表带 community_id 列，拦截器自动过滤绑定社区）。
     * size 钳制到 [1,100]，防御 page/size 传 0 或负数产生非法 LIMIT（DEF-127）。
     */
    public PageVO<LeaseMoveOutApplicationVO> page(long page, long size, String status, Long communityId) {
        LambdaQueryWrapper<LeaseMoveOutApplication> wrapper = new LambdaQueryWrapper<LeaseMoveOutApplication>()
                .eq(StringUtils.hasText(status), LeaseMoveOutApplication::getStatus, status)
                .eq(communityId != null, LeaseMoveOutApplication::getCommunityId, communityId)
                .orderByDesc(LeaseMoveOutApplication::getId);
        Page<LeaseMoveOutApplication> result = moveOutMapper.selectPage(
                new Page<>(page, Math.max(1, Math.min(size, 100))), wrapper);
        /* 一次批量装载装配上下文（DEF-127），消除逐行 selectById 的 N+1 */
        AssemblyContext ctx = loadAssemblyContext(result.getRecords());
        return PageVO.of(result.convert(record -> toVO(record, ctx)));
    }

    /** 本人退租申请列表（居民端租约卡片展示申请进度，按申请时间倒序） */
    public List<LeaseMoveOutApplicationVO> listByResident() {
        List<LeaseMoveOutApplication> records = moveOutMapper.selectList(
                new LambdaQueryWrapper<LeaseMoveOutApplication>()
                        .eq(LeaseMoveOutApplication::getResidentId, SecurityUtils.getUserId())
                        .orderByDesc(LeaseMoveOutApplication::getId));
        /* 一次批量装载装配上下文（DEF-127），消除逐行 selectById 的 N+1 */
        AssemblyContext ctx = loadAssemblyContext(records);
        return records.stream().map(record -> toVO(record, ctx)).toList();
    }

    /**
     * 审核通过（ADMIN/SUPER_ADMIN）：租约 ACTIVE→MOVED_OUT + 居住关系迁出 +
     * house.status=VACANT + 在架房源状态回收 + change_log(STATUS) + 关闭租约群聊 + 通知居民。
     * 幂等：已通过直接返回；已驳回不得再通过；并发抢占失败方按幂等/冲突分支返回。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "REVIEW",
            targetType = "LEASE_MOVE_OUT_APPLICATION", targetId = "#id", content = "'退租申请审核通过'")
    public LeaseMoveOutApplicationVO approve(Long id, ApproveMoveOutDTO dto) {
        LeaseMoveOutApplication application = requireApplication(id);
        SecurityUtils.checkCommunityAccess(application.getCommunityId());
        if (MoveOutStatus.APPROVED.equals(application.getStatus())) {
            return toVO(application);
        }
        if (!MoveOutStatus.PENDING.equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "当前退租申请状态不可通过：" + application.getStatus());
        }
        LeaseRecord lease = requireLease(application.getLeaseId());
        if (!LeaseStatus.ACTIVE.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.LEASE_STATUS_INVALID,
                    "仅已生效租约可办理退租，当前状态：" + lease.getStatus());
        }

        /* ① 终态条件更新（DEF-104）：UPDATE ... WHERE id=? AND status='PENDING' 抢占，
           0 行即并发赢家已处理——重读后已 APPROVED 视为幂等成功（不重复副作用链），
           否则按「已被处理」拒绝。跨域副作用链只在抢占成功后执行 */
        Long reviewerId = SecurityUtils.getUserId();
        LocalDateTime reviewTime = LocalDateTime.now();
        int rows = moveOutMapper.update(null, new LambdaUpdateWrapper<LeaseMoveOutApplication>()
                .eq(LeaseMoveOutApplication::getId, application.getId())
                .eq(LeaseMoveOutApplication::getStatus, MoveOutStatus.PENDING)
                .set(LeaseMoveOutApplication::getStatus, MoveOutStatus.APPROVED)
                .set(LeaseMoveOutApplication::getReviewerId, reviewerId)
                .set(LeaseMoveOutApplication::getReviewTime, reviewTime));
        if (rows == 0) {
            LeaseMoveOutApplication fresh = moveOutMapper.selectById(application.getId());
            if (fresh != null && MoveOutStatus.APPROVED.equals(fresh.getStatus())) {
                return toVO(fresh);
            }
            throw new BusinessException(ErrorCode.MOVE_OUT_APPLICATION_PENDING,
                    "退租申请已被处理，当前状态：" + (fresh == null ? "未知" : fresh.getStatus()));
        }
        application.setStatus(MoveOutStatus.APPROVED);
        application.setReviewerId(reviewerId);
        application.setReviewTime(reviewTime);
        application.setRejectReason(null);

        /* ② 居住关系迁出：该租客在本租约房屋下未迁出的关系（同房屋可能有多条，逐条终止） */
        List<ResidenceRelation> relations = relationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getResidentId, application.getResidentId())
                .eq(ResidenceRelation::getHouseId, application.getHouseId())
                .isNull(ResidenceRelation::getMoveOutDate));
        if (relations.isEmpty()) {
            log.warn("退租通过但未找到在住居住关系（历史数据缺失），仅落租约与房态：leaseId={}",
                    application.getLeaseId());
        } else {
            for (ResidenceRelation relation : relations) {
                relation.setMoveOutDate(application.getMoveOutDate());
                relationMapper.updateById(relation);
            }
        }

        /* ③ 租约落已搬出（终态流转留痕，走同一状态机口径） */
        String from = lease.getStatus();
        lease.setStatus(LeaseStatus.MOVED_OUT);
        leaseMapper.updateById(lease);
        changeLogService.logStatus(lease.getId(), from, LeaseStatus.MOVED_OUT,
                "退租申请通过，居民于 " + application.getMoveOutDate() + " 搬出");

        /* ④ 房屋置空置（本租约租客已迁出，房态释放回可申请） */
        House house = houseMapper.selectById(application.getHouseId());
        if (house == null) {
            log.warn("退租通过但房屋不存在，跳过房态回置：houseId={}", application.getHouseId());
        } else {
            house.setStatus(HouseStatusConstant.VACANT);
            houseMapper.updateById(house);
        }

        /* ⑤ 在架房源状态回收（housingStatus 可选，缺省 AVAILABLE；无挂牌记录跳过） */
        String housingStatus = StringUtils.hasText(dto.getHousingStatus())
                ? dto.getHousingStatus() : DEFAULT_HOUSING_STATUS;
        int updatedListings = housingMapper.update(null, new LambdaUpdateWrapper<Housing>()
                .set(Housing::getStatus, housingStatus)
                .eq(Housing::getHouseId, application.getHouseId()));
        log.info("在架房源状态回收：houseId={}, housingStatus={}, affected={}",
                application.getHouseId(), housingStatus, updatedListings);

        /* ⑥ 租约群聊关闭（R68 生命周期止于租约完成，CLOSED 后停收停发只读留档） */
        conversationService.closeLeaseGroup(lease.getId());

        notificationService.createForResident(application.getResidentId(), application.getCommunityId(),
                "退租申请已通过",
                "您的退租申请已通过，搬出日期 " + application.getMoveOutDate()
                        + "，租约状态已更新为已搬出。",
                "LEASE", "LEASE_MOVE_OUT", application.getLeaseId());
        log.info("退租申请已通过：applicationId={}, leaseId={}, moveOutDate={}, operator={}",
                id, lease.getId(), application.getMoveOutDate(), reviewerId);
        return toVO(application);
    }

    /**
     * 审核驳回（ADMIN/SUPER_ADMIN）：理由必填（DTO @NotBlank），租约**保持 ACTIVE**，
     * 仅落申请终态并通知居民；驳回后可重新提交申请（PENDING 约束随之释放）。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "REVIEW",
            targetType = "LEASE_MOVE_OUT_APPLICATION", targetId = "#id",
            content = "'退租申请驳回：' + #dto.reason")
    public LeaseMoveOutApplicationVO reject(Long id, RejectMoveOutDTO dto) {
        LeaseMoveOutApplication application = requireApplication(id);
        SecurityUtils.checkCommunityAccess(application.getCommunityId());
        if (!MoveOutStatus.PENDING.equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "仅待审核的退租申请可驳回，当前状态：" + application.getStatus());
        }

        /* 终态条件更新（DEF-104，同 approve 口径）：WHERE id=? AND status='PENDING' 抢占，
           0 行即并发赢家已处理——重读后已 REJECTED 视为幂等成功返回，否则按「已被处理」拒绝 */
        Long reviewerId = SecurityUtils.getUserId();
        LocalDateTime reviewTime = LocalDateTime.now();
        int rows = moveOutMapper.update(null, new LambdaUpdateWrapper<LeaseMoveOutApplication>()
                .eq(LeaseMoveOutApplication::getId, application.getId())
                .eq(LeaseMoveOutApplication::getStatus, MoveOutStatus.PENDING)
                .set(LeaseMoveOutApplication::getStatus, MoveOutStatus.REJECTED)
                .set(LeaseMoveOutApplication::getReviewerId, reviewerId)
                .set(LeaseMoveOutApplication::getReviewTime, reviewTime)
                .set(LeaseMoveOutApplication::getRejectReason, dto.getReason()));
        if (rows == 0) {
            LeaseMoveOutApplication fresh = moveOutMapper.selectById(application.getId());
            if (fresh != null && MoveOutStatus.REJECTED.equals(fresh.getStatus())) {
                return toVO(fresh);
            }
            throw new BusinessException(ErrorCode.MOVE_OUT_APPLICATION_PENDING,
                    "退租申请已被处理，当前状态：" + (fresh == null ? "未知" : fresh.getStatus()));
        }
        application.setStatus(MoveOutStatus.REJECTED);
        application.setReviewerId(reviewerId);
        application.setReviewTime(reviewTime);
        application.setRejectReason(dto.getReason());

        notificationService.createForResident(application.getResidentId(), application.getCommunityId(),
                "退租申请未通过",
                "您的退租申请未通过：" + dto.getReason() + "，租约继续有效。",
                "LEASE", "LEASE_MOVE_OUT", application.getLeaseId());
        log.info("退租申请已驳回：applicationId={}, reason={}, operator={}",
                id, dto.getReason(), reviewerId);
        return toVO(application);
    }

    public LeaseMoveOutApplication requireApplication(Long id) {
        LeaseMoveOutApplication application = moveOutMapper.selectById(id);
        if (application == null) {
            throw new ResourceNotFoundException("退租申请不存在");
        }
        return application;
    }

    private LeaseRecord requireLease(Long leaseId) {
        LeaseRecord lease = leaseMapper.selectById(leaseId);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        return lease;
    }

    /* 社区绑定管理员 ID 集合（通知口径同到期提醒任务） */
    private List<Long> boundAdminIds(Long communityId) {
        return sysAdminCommunityMapper.selectList(new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getCommunityId, communityId))
                .stream().map(SysAdminCommunity::getAdminId).toList();
    }

    /* VO 装配：补申请人姓名、社区名与房屋位置（列表页展示需要）。
       单记录路径与批量路径共用一套装配逻辑（DEF-127），仅装载方式不同 */
    private LeaseMoveOutApplicationVO toVO(LeaseMoveOutApplication application) {
        return toVO(application, loadAssemblyContext(List.of(application)));
    }

    private LeaseMoveOutApplicationVO toVO(LeaseMoveOutApplication application, AssemblyContext ctx) {
        LeaseMoveOutApplicationVO vo = LeaseMoveOutApplicationVO.from(application);
        Resident resident = byId(ctx.residents(), application.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        Community community = byId(ctx.communities(), application.getCommunityId());
        if (community != null) {
            vo.setCommunityName(community.getName());
        }
        House house = byId(ctx.houses(), application.getHouseId());
        if (house != null) {
            Unit unit = byId(ctx.units(), house.getUnitId());
            if (unit == null) {
                vo.setHouseLocation(house.getHouseNumber());
            } else {
                Building building = byId(ctx.buildings(), unit.getBuildingId());
                vo.setHouseLocation((building != null ? building.getName() : "")
                        + unit.getName() + house.getHouseNumber());
            }
        }
        if (application.getReviewerId() != null) {
            SysUser reviewer = byId(ctx.reviewers(), application.getReviewerId());
            if (reviewer != null) {
                vo.setReviewerName(StringUtils.hasText(reviewer.getRealName())
                        ? reviewer.getRealName() : reviewer.getUsername());
            }
        }
        return vo;
    }

    /* VO 装配上下文（DEF-127）：按 distinct id 批量装载六类关联实体，
       消除列表页逐行 selectById 的 N+1（口径同 ConversationService.participantProfiles：
       空集合不查库——selectBatchIds 空列表行为不保证） */
    private record AssemblyContext(
            Map<Long, Resident> residents,
            Map<Long, Community> communities,
            Map<Long, House> houses,
            Map<Long, Unit> units,
            Map<Long, Building> buildings,
            Map<Long, SysUser> reviewers) {
    }

    private AssemblyContext loadAssemblyContext(List<LeaseMoveOutApplication> records) {
        if (records.isEmpty()) {
            return new AssemblyContext(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<Long, Resident> residents = batchMap(
                distinctIds(records, LeaseMoveOutApplication::getResidentId),
                residentMapper::selectBatchIds, Resident::getId);
        Map<Long, Community> communities = batchMap(
                distinctIds(records, LeaseMoveOutApplication::getCommunityId),
                communityMapper::selectBatchIds, Community::getId);
        Map<Long, House> houses = batchMap(
                distinctIds(records, LeaseMoveOutApplication::getHouseId),
                houseMapper::selectBatchIds, House::getId);
        Map<Long, Unit> units = batchMap(
                distinctIds(houses.values(), House::getUnitId),
                unitMapper::selectBatchIds, Unit::getId);
        Map<Long, Building> buildings = batchMap(
                distinctIds(units.values(), Unit::getBuildingId),
                buildingMapper::selectBatchIds, Building::getId);
        Map<Long, SysUser> reviewers = batchMap(
                distinctIds(records, LeaseMoveOutApplication::getReviewerId),
                sysUserMapper::selectBatchIds, SysUser::getId);
        return new AssemblyContext(residents, communities, houses, units, buildings, reviewers);
    }

    /* 批量装载为 id → 实体索引：装载器即 mapper::selectBatchIds，键提取器即实体 id */
    private static <T> Map<Long, T> batchMap(List<Long> ids,
            Function<List<Long>, List<T>> loader, Function<T, Long> keyExtractor) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream()
                .filter(item -> keyExtractor.apply(item) != null)
                .collect(Collectors.toMap(keyExtractor, Function.identity(), (a, b) -> a));
    }

    private static <S> List<Long> distinctIds(Collection<S> sources, Function<S, Long> extractor) {
        return sources.stream().map(extractor).filter(Objects::nonNull).distinct().toList();
    }

    /* null 键安全取值：Map.of() 的不可变实现不容忍 null 探测 */
    private static <T> T byId(Map<Long, T> map, Long id) {
        return id == null ? null : map.get(id);
    }

    private void unlockQuietly(RLock lock) {
        try {
            lock.unlock();
        } catch (Exception e) {
            log.warn("退租并发锁释放异常（锁键已过期时正常）", e);
        }
    }
}
