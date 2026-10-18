package com.community.residence.common.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.resident.constant.ApplicationStatus;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.community.constant.HouseStatusConstant;
import com.community.residence.common.lease.constant.LeaseStatus;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.entity.Building;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.Unit;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.dto.ApproveApplicationDTO;
import com.community.residence.common.resident.dto.CreateApplicationDTO;
import com.community.residence.common.resident.dto.RejectApplicationDTO;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.vo.ApplicationVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 入住申请业务逻辑：PENDING → APPROVED / REJECTED / WITHDRAWN 状态机（终态不可变更，R66 增撤回）。
 * 审批通过（人工或社区自动）租客仅建立待签约租约与群聊，不提前正式居住关系；
 * **审核通过 ≠ 租约生效**（R66 v1.8 口径）：租约落 TO_SIGN 待签约、房屋保持空置，
 * 待协议完成且首期成功才建立正式关系、置ACTIVE与房屋已入住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResidenceApplicationService {

    /** 自动通过路径的兜底默认租期（月）：社区未配置时使用 */
    private static final int DEFAULT_LEASE_MONTHS = 12;

    /** 会阻止同一房屋继续生成新入住业务链的租约状态。 */
    private static final Set<String> BLOCKING_LEASE_STATUSES =
            Set.of(LeaseStatus.PENDING, LeaseStatus.TO_SIGN, LeaseStatus.ACTIVE);

    private final ResidenceApplicationMapper applicationMapper;
    private final ResidenceRelationMapper relationMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final LeaseRecordMapper leaseRecordMapper;
    private final HousingMapper housingMapper;
    private final com.community.residence.common.housing.mapper.ViewingAppointmentMapper viewingAppointmentMapper;
    private final CommunityService communityService;
    private final ConversationService conversationService;
    private final LeaseChangeLogService changeLogService;
    private final NotificationService notificationService;

    /* 提交申请：锁定房屋行后串行检查申请、租约与居住关系，避免多账号抢同一房屋或重复建租约。 */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApplicationVO create(CreateApplicationDTO dto) {
        if (ResidenceDependencyService.isDependent(dto.getRelationType())) throw new BusinessException(ErrorCode.INVALID_PARAM,"家属或共同居住人须由社区管理员依有效主居住关系登记，不单独申请占用房屋");
        House house = lockHouse(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        if (!HouseStatusConstant.VACANT.equals(house.getStatus())
                && !HouseStatusConstant.RESERVED.equals(house.getStatus())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT);
        }

        Long residentId = SecurityUtils.getUserId();
        ensureApplicationAvailable(residentId, dto.getHouseId());

        ResidenceApplication application = new ResidenceApplication();
        application.setResidentId(residentId);
        application.setCommunityId(house.getCommunityId());
        application.setHouseId(house.getId());
        application.setRelationType(dto.getRelationType());
        application.setStatus("PENDING");
        application.setRemark(dto.getRemark());
        if ("TENANT".equals(dto.getRelationType())) {
            if (dto.getHousingId() == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "租客申请必须从具体挂牌进入，来源挂牌不能为空");
            }
            Housing listing = housingMapper.selectByIdForUpdate(dto.getHousingId());
            if (listing == null || !house.getId().equals(listing.getHouseId())
                    || "SALE".equals(listing.getRentType())
                    || !("AVAILABLE".equals(listing.getStatus()) || "RESERVED".equals(listing.getStatus()))) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "来源挂牌不属于申请房屋、不是出租或已下线");
            }
            communityService.requireActiveCommunity(house.getCommunityId());
            application.setHousingId(listing.getId());
            application.setListedMonthlyRent(listing.getMonthlyRent());
            application.setListedDeposit(listing.getDeposit());
        }
        applicationMapper.insert(application);

        /* 社区开启「入住申请自动通过」（R62 补，社区级开关）：提交即完成审批，
           免去人工审核；租期按社区默认月数推导，租金与押金取明确来源挂牌的申请时快照 */
        Community community = communityService.requireCommunity(house.getCommunityId());
        if (community.getAutoApproveResidence() != null && community.getAutoApproveResidence() == 1) {
            autoApprove(application, house, community);
        }
        return toVO(application);
    }

    /* 自动通过：与人工审批走同一内核，仅租期与租金来源不同、审核人留空（无人工审核者） */
    private void autoApprove(ResidenceApplication application, House house, Community community) {
        int months = community.getDefaultLeaseMonths() == null || community.getDefaultLeaseMonths() < 1
                ? DEFAULT_LEASE_MONTHS : community.getDefaultLeaseMonths();
        LocalDate startDate = LocalDate.now();
        BigDecimal monthlyRent = application.getListedMonthlyRent() != null ? application.getListedMonthlyRent() : BigDecimal.ZERO;
        BigDecimal deposit = application.getListedDeposit();
        applyApproval(application, house, startDate, startDate.plusMonths(months).minusDays(1), monthlyRent, deposit,
                null, "社区已开启入住申请自动通过，系统自动审批", months, 1, 1);
        log.info("入住申请自动通过：applicationId={}, houseId={}, leaseMonths={}, rent={}",
                application.getId(), house.getId(), months, monthlyRent);
    }

    /** 申请详情：居民限本人，ADMIN 限绑定社区 */
    public ApplicationVO getById(Long id) {
        ResidenceApplication application = requireApplication(id);
        checkReadAccess(application);
        return toVO(application);
    }

    public PageVO<ApplicationVO> page(long page, long size, String status) {
        LambdaQueryWrapper<ResidenceApplication> wrapper = new LambdaQueryWrapper<ResidenceApplication>()
                .eq(StringUtils.hasText(status), ResidenceApplication::getStatus, status)
                .orderByDesc(ResidenceApplication::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(ResidenceApplication::getResidentId, SecurityUtils.getUserId());
        }
        Page<ResidenceApplication> result = applicationMapper.selectPage(
                new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 审批通过：建立居住关系 + 租住记录 + 房屋状态翻转，同一事务 */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "REVIEW", targetType = "RESIDENCE_APPLICATION", targetId = "#id", content = "'入住申请审核通过'")
    public ApplicationVO approve(Long id, ApproveApplicationDTO dto) {
        ResidenceApplication application = requireApplication(id);
        checkReviewAccess(application);
        if (!"PENDING".equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED);
        }
        /* 先锁定房屋，再抢占申请终态：提交申请与管理员审批共用同一房屋锁，
           避免审批已将申请改为 APPROVED、但租约尚未写入时被另一请求插入第二条申请。 */
        House house = lockHouse(application.getHouseId());
        if (house == null || HouseStatusConstant.OCCUPIED.equals(house.getStatus())) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已被入住，无法通过申请");
        }
        ensureApprovalAvailable(application);
        if (!leaseRecordMapper.selectBlockingIdsForUpdate(application.getHouseId()).isEmpty()) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已有进行中的租约，不能重复审核");
        }
        /* 抢占式终态条件更新（OCR DEF-117）：并发「管理员审批 × 居民撤回」都读到 PENDING 时，
           仅一方能落 APPROVED（WHERE status='PENDING' 抢占）；0 行即并发赢家已处理——
           重读后已 APPROVED 视为重复审批幂等返回既有结果，其余终态按已审核拒绝。
           抢占成功后本事务内继续跑审批内核，失败整体回滚（含抢占行） */
        int claimed = applicationMapper.update(null, new LambdaUpdateWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getId, id)
                .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING)
                .set(ResidenceApplication::getStatus, ApplicationStatus.APPROVED));
        if (claimed == 0) {
            ResidenceApplication fresh = applicationMapper.selectById(id);
            if (fresh != null && ApplicationStatus.APPROVED.equals(fresh.getStatus())) {
                log.info("重复审批按幂等处理，直接返回既有结果：applicationId={}", id);
                return toVO(fresh);
            }
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED,
                    "申请已被处理（当前状态：" + (fresh == null ? "未知" : fresh.getStatus()) + "）");
        }
        if (dto.getLeaseEndDate()!=null && dto.getLeaseEndDate().isBefore(dto.getLeaseStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期不能早于开始日期");
        }
        var sourceCommunity=communityService.requireCommunity(application.getCommunityId());
        int total=com.community.residence.common.lease.service.LeaseTermRules.months(dto.getContractMonths(),sourceCommunity==null||sourceCommunity.getDefaultLeaseMonths()==null?12:sourceCommunity.getDefaultLeaseMonths());
        int first=com.community.residence.common.lease.service.LeaseTermRules.months(dto.getFirstPaymentMonths(),1),cycle=com.community.residence.common.lease.service.LeaseTermRules.months(dto.getPaymentCycleMonths(),1);
        com.community.residence.common.lease.service.LeaseTermRules.validate(total,first,cycle);
        applyApproval(application, house, dto.getLeaseStartDate(), com.community.residence.common.lease.service.LeaseTermRules.end(dto.getLeaseStartDate(),total),
                dto.getMonthlyRent(), dto.getDeposit(), SecurityUtils.getUserId(), dto.getRemark(), total, first, cycle);
        log.info("入住申请已通过：applicationId={}, operator={}", id, SecurityUtils.getUserId());
        return toVO(application);
    }

    /**
     * 审批通过内核：TENANT 只建待签约租约及群聊；非租客建立居住关系；申请落终态。
     * 人工审批与社区自动审批共用，差异仅在租期/租金来源与审核人是否留痕（自动审批无人工审核者）。
     * R66 v1.8：**不**翻转房屋状态（协议完成且首期成功时才置 OCCUPIED）；群聊生命周期开始于审核通过，
     * 参与者 = 申请居民 + 该社区全部启用 ADMIN，会话 ID 回写 lease_record.conversation_id。
     */
    private void applyApproval(ResidenceApplication application, House house, LocalDate startDate,
                               LocalDate endDate, BigDecimal monthlyRent, BigDecimal deposit,
                               Long reviewerId, String reviewRemark, int total, int first, int cycle) {
        if (ResidenceDependencyService.isDependent(application.getRelationType())) throw new BusinessException(ErrorCode.INVALID_PARAM,"同住关系须通过登记同住人员入口依有效主关系建立");
        /* 居住关系：TENANT 建租住记录；OWNER/FAMILY 建关系即可（租约由 C3 独立登记） */
        ResidenceRelation relation = null;
        if (!"TENANT".equals(application.getRelationType())) {
            relation = new ResidenceRelation();
            relation.setResidentId(application.getResidentId());
            relation.setCommunityId(application.getCommunityId());
            relation.setHouseId(application.getHouseId());
            relation.setRelationType(application.getRelationType());
            relation.setMoveInDate(startDate);
            relation.setIsPrimary(1);
            relationMapper.insert(relation);
        }

        if ("TENANT".equals(application.getRelationType())) {
            String houseLocation = buildHouseLocation(house);
            LeaseRecord lease = new LeaseRecord();
            lease.setTenantId(application.getResidentId());
            lease.setCommunityId(application.getCommunityId());
            lease.setHouseId(application.getHouseId());
            lease.setStartDate(startDate);
            /* 保留已约定月数，不能从月末截短后的日期反算月份。 */
            lease.setContractMonths(total);lease.setFirstPaymentMonths(first);lease.setPaymentCycleMonths(cycle);
            lease.setEndDate(com.community.residence.common.lease.service.LeaseTermRules.end(startDate,total));
            lease.setMonthlyRent(monthlyRent);
            lease.setDeposit(deposit);
            lease.setStatus(LeaseStatus.TO_SIGN);
            lease.setAgreementStatus("NONE");
            lease.setRemark("入住申请审批通过自动建立");
            leaseRecordMapper.insert(lease);
            changeLogService.logCreate(lease, "入住申请审批通过自动建立（待签约）");
            LeaseRecord listedPrice = new LeaseRecord();
            listedPrice.setId(lease.getId()); listedPrice.setStartDate(startDate); listedPrice.setEndDate(endDate);
            listedPrice.setMonthlyRent(application.getListedMonthlyRent()); listedPrice.setDeposit(application.getListedDeposit());
            listedPrice.setRemark(lease.getRemark());
            changeLogService.logAttributeChanges(listedPrice, lease,
                    "来源挂牌#" + application.getHousingId() + "申请时价对照；审核备注：" + (reviewRemark == null ? "" : reviewRemark));

            /* R68 租约群聊：审核通过即建群（幂等），协议确认与租住沟通在该会话内进行 */
            Conversation conversation = conversationService.createLeaseGroup(lease.getId(),
                    application.getCommunityId(), application.getResidentId(),
                    "租约沟通·" + houseLocation);
            if (conversation != null) {
                lease.setConversationId(conversation.getId());
                leaseRecordMapper.updateById(lease);
            }
            /* V26 强关联（DEF-080）：把"本申请生成的那份租约"写死到申请上（同一事务，
               人工审批与社区自动审批共用本内核，故两条链路都覆盖）——
               杜绝前端按"同一居民/社区/房屋 取最新"反查在同屋多租约时指错 */
            application.setLeaseId(lease.getId());

            /* 看房 → 租约 指向修正（DEF-081）：把该居民该房屋下 lease_id 仍为空的看房预约
               补写成本次生成的租约。只补指向、不动看房状态——看房仍是沟通手段、不是承约门槛；
               `lease_id IS NULL` 保证幂等（重复审批不重写、不覆盖既有非空指向）。 */
            int linked = viewingAppointmentMapper.linkToLease(lease.getId(),
                    application.getResidentId(), application.getHouseId());
            if (linked > 0) {
                log.info("看房预约已指向本次生成的租约：applicationId={}, leaseId={}, 补写行数={}",
                        application.getId(), lease.getId(), linked);
            }
            notificationService.createForResident(application.getResidentId(), application.getCommunityId(),
                    "入住申请已通过",
                    "您的入住申请（" + houseLocation + "）已审批通过，租约待签约，请查看管理方发出的协议并确认同意，首期费用支付成功后正式入住",
                    "LEASE", "LEASE", lease.getId());
        } else {
            notificationService.createForResident(application.getResidentId(), application.getCommunityId(),
                    "入住申请已通过",
                    "您的入住申请（" + buildHouseLocation(house) + "）已审批通过",
                    "RESIDENCE", "RESIDENCE_APPLICATION", application.getId());
        }

        application.setStatus(ApplicationStatus.APPROVED);
        application.setReviewerId(reviewerId);
        application.setReviewTime(LocalDateTime.now());
        application.setReviewRemark(reviewRemark);
        applicationMapper.updateById(application);
        log.info("入住申请审批内核完成：applicationId={}, relationId={}, reviewer={}",
                application.getId(), relation == null ? null : relation.getId(), reviewerId);
    }

    /**
     * 锁定房屋行。单元测试中旧用例只 stub 通用查询时允许回退；生产环境优先使用 FOR UPDATE。
     */
    private House lockHouse(Long houseId) {
        House locked = houseMapper.selectByIdForUpdate(houseId);
        return locked != null ? locked : houseMapper.selectById(houseId);
    }

    /* 提交前的唯一性保护：同一房屋只允许一条待处理申请，已有租约或在住关系不得重复开启租住链。 */
    private void ensureApplicationAvailable(Long residentId, Long houseId) {
        Long residentPending = applicationMapper.selectCount(new LambdaQueryWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getResidentId, residentId)
                .eq(ResidenceApplication::getHouseId, houseId)
                .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING));
        if (residentPending > 0) {
            throw new BusinessException(ErrorCode.APPLICATION_PENDING_EXISTS);
        }

        Long housePending = applicationMapper.selectCount(new LambdaQueryWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getHouseId, houseId)
                .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING));
        if (housePending > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "该房屋已有待处理入住申请，请稍后再试");
        }

        Long leaseConflict = leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getHouseId, houseId)
                .in(LeaseRecord::getStatus, BLOCKING_LEASE_STATUSES));
        if (leaseConflict > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "该房屋已有进行中的租约，无法重复申请");
        }

        Long activeRelation = relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getResidentId, residentId)
                .eq(ResidenceRelation::getHouseId, houseId)
                .isNull(ResidenceRelation::getMoveOutDate));
        if (activeRelation > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "您与该房屋已有有效居住关系，无法重复申请");
        }
    }

    /* 审批前再次检查写入竞争，防止历史多申请或旁路租约绕过提交端保护。 */
    private void ensureApprovalAvailable(ResidenceApplication application) {
        Long leaseConflict = leaseRecordMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getHouseId, application.getHouseId())
                .in(LeaseRecord::getStatus, BLOCKING_LEASE_STATUSES));
        if (leaseConflict > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已有进行中的租约，无法通过当前申请");
        }

        Long activeRelation = relationMapper.selectCount(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, application.getHouseId())
                .isNull(ResidenceRelation::getMoveOutDate));
        if (activeRelation > 0) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已有在住居住关系，无法通过当前申请");
        }
    }

    /**
     * 撤回申请（R66 v1.8）：仅申请人本人、仅 PENDING 可撤回，落 WITHDRAWN 终态；
     * 若该申请已产生租约群聊则一并关闭（群聊生命周期止于申请撤回）。
     * `lease_id` **保留不清空**：撤回只允许发生在 PENDING（正常为 NULL），
     * 若旁路数据已有关联，保留它是"这份租约曾由此申请产生"的审计线索，清空反而丢证据。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "RESIDENCE_APPLICATION", targetId = "#id", content = "'撤回入住申请'")
    public ApplicationVO withdraw(Long id) {
        ResidenceApplication application = requireApplication(id);
        if (!SecurityUtils.hasRole(RoleConstants.RESIDENT)
                || !application.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("仅申请人本人可撤回申请");
        }
        if (!ApplicationStatus.PENDING.equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.APPLICATION_NOT_WITHDRAWABLE);
        }
        /* 抢占式终态条件更新（OCR DEF-117）：并发「居民撤回 × 管理员审批」都读到 PENDING 时，
           仅一方能落 WITHDRAWN（WHERE status='PENDING' 抢占）；0 行即并发赢家已处理——
           重读后已 WITHDRAWN 视为重复撤回幂等成功，其余终态按不可撤回拒绝 */
        int claimed = applicationMapper.update(null, new LambdaUpdateWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getId, id)
                .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING)
                .set(ResidenceApplication::getStatus, ApplicationStatus.WITHDRAWN));
        if (claimed == 0) {
            ResidenceApplication fresh = applicationMapper.selectById(id);
            if (fresh != null && ApplicationStatus.WITHDRAWN.equals(fresh.getStatus())) {
                log.info("重复撤回按幂等处理，直接返回既有终态：applicationId={}", id);
                return toVO(fresh);
            }
            throw new BusinessException(ErrorCode.APPLICATION_NOT_WITHDRAWABLE,
                    "申请已被处理（当前状态：" + (fresh == null ? "未知" : fresh.getStatus()) + "），无法撤回");
        }
        application.setStatus(ApplicationStatus.WITHDRAWN);
        application.setReviewTime(LocalDateTime.now());
        application.setReviewRemark("申请人主动撤回");
        applicationMapper.updateById(application);

        /* 待审核申请正常无租约（lease_id 为空）；存量数据经「先通过后退回待审」等旁路可能已有
           租约群聊，此时允许回退弱关联定位（**仅本副作用路径**，见 leaseOf 注释） */
        LeaseRecord lease = leaseOf(application, true);
        if (lease == null) {
            log.warn("撤回申请未匹配到租约，无需关闭群聊：applicationId={}", id);
            return toVO(application);
        }
        /* 弱回退关群收窄（OCR DEF-117）：弱命中（同居民/社区/房屋最新租约）若仍处活跃态
           （TO_SIGN/ACTIVE），该群是**另一份已批申请**的现行沟通渠道——本次撤回的是别的申请，
           不得误关；强关联租约（lease_id 直挂本申请）不受此限，其群生命周期本就止于本申请撤回 */
        boolean strongLink = application.getLeaseId() != null
                && application.getLeaseId().equals(lease.getId());
        if (!strongLink && (LeaseStatus.TO_SIGN.equals(lease.getStatus())
                || LeaseStatus.ACTIVE.equals(lease.getStatus()))) {
            log.info("撤回申请弱命中租约仍处活跃态，跳过关闭其群聊：applicationId={}, leaseId={}, leaseStatus={}",
                    id, lease.getId(), lease.getStatus());
            return toVO(application);
        }
        conversationService.closeLeaseGroup(lease.getId());
        log.info("入住申请已撤回：applicationId={}, leaseId={}, operator={}",
                id, lease.getId(), SecurityUtils.getUserId());
        return toVO(application);
    }

    /* 审批拒绝：终态不可变更 */
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "REVIEW", targetType = "RESIDENCE_APPLICATION", targetId = "#id", content = "'入住申请驳回：' + #dto.reason")
    public void reject(Long id, RejectApplicationDTO dto) {
        ResidenceApplication application = requireApplication(id);
        checkReviewAccess(application);
        if (!"PENDING".equals(application.getStatus())) {
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED);
        }
        /* 抢占式终态条件更新（OCR DEF-117）：与 approve/withdraw 同口径，防止并发驳回
           覆盖撤回/审批的终态（updateById 全字段写回会静默改掉已落终态） */
        int claimed = applicationMapper.update(null, new LambdaUpdateWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getId, id)
                .eq(ResidenceApplication::getStatus, ApplicationStatus.PENDING)
                .set(ResidenceApplication::getStatus, ApplicationStatus.REJECTED));
        if (claimed == 0) {
            ResidenceApplication fresh = applicationMapper.selectById(id);
            if (fresh != null && ApplicationStatus.REJECTED.equals(fresh.getStatus())) {
                log.info("重复驳回按幂等处理，直接返回：applicationId={}", id);
                return;
            }
            throw new BusinessException(ErrorCode.APPLICATION_ALREADY_REVIEWED,
                    "申请已被处理（当前状态：" + (fresh == null ? "未知" : fresh.getStatus()) + "）");
        }
        application.setStatus(ApplicationStatus.REJECTED);
        application.setReviewerId(SecurityUtils.getUserId());
        application.setReviewTime(LocalDateTime.now());
        application.setReviewRemark(dto.getReason());
        applicationMapper.updateById(application);
        notificationService.createForResident(application.getResidentId(), application.getCommunityId(),
                "入住申请未通过", "您的入住申请未通过：" + dto.getReason(),
                "RESIDENCE", "RESIDENCE_APPLICATION", id);
        log.info("入住申请已拒绝：applicationId={}, reason={}, operator={}",
                id, dto.getReason(), SecurityUtils.getUserId());
    }

    public ResidenceApplication requireApplication(Long id) {
        ResidenceApplication application = applicationMapper.selectById(id);
        if (application == null) {
            throw new ResourceNotFoundException("入住申请不存在");
        }
        return application;
    }

    /* 读权限：RESIDENT 限本人；ADMIN 限绑定社区；STAFF 只读放行 */
    private void checkReadAccess(ResidenceApplication application) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !application.getResidentId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人申请");
        }
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            SecurityUtils.checkCommunityAccess(application.getCommunityId());
        }
    }

    /* 审核权限：ADMIN 限绑定社区（SUPER_ADMIN 放行） */
    private void checkReviewAccess(ResidenceApplication application) {
        SecurityUtils.checkCommunityAccess(application.getCommunityId());
        communityService.requireCommunity(application.getCommunityId());
    }

    private ApplicationVO toVO(ResidenceApplication application) {
        ApplicationVO vo = ApplicationVO.from(application);
        Resident resident = residentMapper.selectById(application.getResidentId());
        if (resident != null) {
            vo.setResidentName(resident.getRealName());
        }
        House house = houseMapper.selectById(application.getHouseId());
        if (house != null) {
            vo.setHouseLocation(buildHouseLocation(house));
        }
        /* V26 强关联（DEF-080）：leaseId 直接取申请上的记录列，**不回退弱关联猜测**——
           同屋多租约时"取最新"会指错，正是本次裁决要修的问题；存量 NULL 行返回空，
           由前端按 status 收口（"无关联租约/请从租约列表进入"）。
           conversationId 取该租约自己的 conversation_id。 */
        if (application.getLeaseId() != null) {
            LeaseRecord lease = leaseRecordMapper.selectById(application.getLeaseId());
            if (lease != null) {
                vo.setConversationId(lease.getConversationId());
            } else {
                log.warn("入住申请关联租约不存在（悬空关联，疑似人工改库）：applicationId={}, leaseId={}",
                        application.getId(), application.getLeaseId());
            }
        }
        return vo;
    }

    /**
     * 申请对应租约（**单一口径实现**）：
     * ①强关联优先——V26 起由审批通过内核写死的 `application.lease_id`；
     * ②`allowWeakFallback=true` 时，仅当强关联为空，才回退到弱关联
     *   （同一 居民/社区/房屋 的最新租约，与 V26 存量回填口径一致）并 WARN 留痕。
     * 该回退**只允许用于撤回关闭群聊这一副作用路径**；VO 取值一律 `false`，避免两套口径
     * 并存、也避免把"靠猜"重新引入读路径。
     */
    private LeaseRecord leaseOf(ResidenceApplication application, boolean allowWeakFallback) {
        if (application.getLeaseId() != null) {
            LeaseRecord linked = leaseRecordMapper.selectById(application.getLeaseId());
            if (linked != null) {
                return linked;
            }
            log.warn("申请强关联租约不存在，按{}处理：applicationId={}, leaseId={}",
                    allowWeakFallback ? "弱关联兜底" : "空关联",
                    application.getId(), application.getLeaseId());
            if (!allowWeakFallback) {
                return null;
            }
        } else if (!allowWeakFallback) {
            return null;
        }
        LeaseRecord weak = leaseRecordMapper.selectOne(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getTenantId, application.getResidentId())
                .eq(LeaseRecord::getCommunityId, application.getCommunityId())
                .eq(LeaseRecord::getHouseId, application.getHouseId())
                .orderByDesc(LeaseRecord::getId)
                .last("LIMIT 1"));
        if (weak != null) {
            log.warn("申请无强关联租约，回退弱关联（同居民/社区/房屋最新租约，可能不精确）：applicationId={}, leaseId={}",
                    application.getId(), weak.getId());
        }
        return weak;
    }

    /** 拼装「楼栋-单元-房号」位置描述 */
    private String buildHouseLocation(House house) {
        Unit unit = unitMapper.selectById(house.getUnitId());
        if (unit == null) {
            return house.getHouseNumber();
        }
        Building building = buildingMapper.selectById(unit.getBuildingId());
        String buildingName = building != null ? building.getName() : "";
        return buildingName + unit.getName() + house.getHouseNumber();
    }
}
