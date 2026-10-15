package com.community.residence.lease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.HouseStatusConstant;
import com.community.residence.common.constant.LeaseStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.lease.dto.CreateLeaseDTO;
import com.community.residence.lease.dto.RenewLeaseDTO;
import com.community.residence.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.vo.LeaseVO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租住业务逻辑：待审核→待签约→已生效→已搬出→已归档 + 已驳回 状态机（架构设计 §6.2 六态）。
 * 「即将到期/已到期」为日期自动判定标注（expiryFlag），不是状态流转。
 * RESIDENT 数据权限在查询层显式按 tenant_id 约束。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaseService {

    /** 状态机合法流转表 */
    /* 状态机（架构 §6.2 六态，R66 v1.8 增「待签约」）：待审核→待签约→已生效→已搬出→已归档；
       待签约亦可被驳回；DEF-013 删除 ACTIVE→ARCHIVED 跳级边（未搬出不可归档）；
       OCR DEF-115 删除 PENDING→ACTIVE 边——冻结状态机 v1.8 无此边（审核通过 ≠ 租约生效，
       生效仅经「管理方同意并发出 + 居民确认具体协议」闭环），审核通过只落到 TO_SIGN */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "PENDING", Set.of("TO_SIGN", "REJECTED"),
            "TO_SIGN", Set.of("ACTIVE", "REJECTED"),
            "ACTIVE", Set.of("MOVED_OUT"),
            "MOVED_OUT", Set.of("ARCHIVED"),
            "ARCHIVED", Set.of(),
            "REJECTED", Set.of());

    /** 即将到期标注窗口（天） */
    private static final int EXPIRING_WINDOW_DAYS = 30;

    /** 管理方直接登记租约时需要阻止的重叠状态。 */
    private static final Set<String> BLOCKING_CREATE_STATUSES =
            Set.of(LeaseStatus.PENDING, LeaseStatus.TO_SIGN, LeaseStatus.ACTIVE);

    private final com.community.residence.resident.service.ResidenceDependencyService residenceDependencyService;
    @org.springframework.beans.factory.annotation.Autowired
    private LeaseContractTermService termService;
    private final LeaseRecordMapper leaseMapper;
    private final ResidentMapper residentMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final com.community.residence.community.mapper.CommunityMapper communityMapper;
    private final LeaseChangeLogService changeLogService;
    private final com.community.residence.payment.mapper.LeasePaymentMapper paymentMapper;
    private final com.community.residence.resident.mapper.ResidenceRelationMapper relationMapper;
    private final com.community.residence.agreement.mapper.LeaseAgreementMapper agreementMapper;
    private final com.community.residence.conversation.service.ConversationService conversationService;


    /* 登记租约：房屋与租客须存在；初始 PENDING 待审核 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "LEASE_RECORD", targetId = "#result.id", content = "'创建租住记录'")
    public LeaseVO create(CreateLeaseDTO dto) {
        Resident tenant = residentMapper.selectById(dto.getResidentId());
        if (tenant == null) {
            throw new ResourceNotFoundException("租客不存在");
        }
        House house = lockHouse(dto.getHouseId());
        if (house == null) {
            throw new ResourceNotFoundException("房屋不存在");
        }
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        if (dto.getEndDate()!=null && dto.getStartDate()!=null && !dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        Long conflict = leaseMapper.selectCount(new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getTenantId, dto.getResidentId())
                .eq(LeaseRecord::getHouseId, dto.getHouseId())
                .in(LeaseRecord::getStatus, BLOCKING_CREATE_STATUSES));
        if (conflict > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "该居民与房屋已有进行中的租约，无法重复登记");
        }

        LeaseRecord lease = new LeaseRecord();
        lease.setTenantId(dto.getResidentId());
        lease.setCommunityId(house.getCommunityId());
        lease.setHouseId(dto.getHouseId());
        lease.setStartDate(dto.getStartDate() == null ? LocalDate.now() : dto.getStartDate());
        var community = communityMapper.selectById(lease.getCommunityId());
        int total = LeaseTermRules.months(dto.getContractMonths(), community==null?12:LeaseTermRules.months(community.getDefaultLeaseMonths(),12));
        int first = LeaseTermRules.months(dto.getFirstPaymentMonths(), 1), cycle = LeaseTermRules.months(dto.getPaymentCycleMonths(), 1);
        LeaseTermRules.validate(total, first, cycle);
        lease.setContractMonths(total);lease.setFirstPaymentMonths(first);lease.setPaymentCycleMonths(cycle);
        lease.setEndDate(LeaseTermRules.end(lease.getStartDate(), total));
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        lease.setContractUrl(dto.getContractUrl());
        lease.setRemark(dto.getRemark());
        lease.setStatus("PENDING");
        leaseMapper.insert(lease);
        changeLogService.logCreate(lease, "管理方登记租约");
        return toVO(lease);
    }

    /**
     * 写入租约前锁定房屋行；旧单元测试未 stub 锁查询时回退到普通查询。
     */
    private House lockHouse(Long houseId) {
        House locked = houseMapper.selectByIdForUpdate(houseId);
        return locked != null ? locked : houseMapper.selectById(houseId);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_RECORD", targetId = "#id", content = "'更新租住记录'")
    public LeaseVO update(Long id, CreateLeaseDTO dto) {
        /* 与发协议共用租约行锁，避免读到旧NONE后覆盖已经发出的协议约定。 */
        LeaseRecord lease = leaseMapper.selectByIdForUpdate(id);
        if (lease == null) throw new ResourceNotFoundException("租住记录不存在");
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        if ("SIGNED".equals(lease.getAgreementStatus()) || "PARTIAL".equals(lease.getAgreementStatus()) || "PENDING".equals(lease.getAgreementStatus()))
            throw new BusinessException(ErrorCode.INVALID_PARAM,"已发出协议的日期、价格和付款安排不可静默修改，请先撤回未完成协议或拟定续租");
        /* R16「归档后不可修改」（DEF-017）：流转端点已有状态机拦截，PUT 旁路补终态校验 */
        if ("ARCHIVED".equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "已归档的租约不可修改");
        }
        if (!lease.getHouseId().equals(dto.getHouseId())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "租约不允许变更房屋");
        }
        if (dto.getEndDate()!=null && dto.getStartDate()!=null && !dto.getEndDate().isAfter(dto.getStartDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        LeaseRecord snapshot = snapshot(lease);
        /* 旧约没有付款安排时保留未知；只有明确录入总月数才重新形成日历月约定。 */
        if (dto.getStartDate() != null) lease.setStartDate(dto.getStartDate());
        Integer total = dto.getContractMonths() == null ? lease.getContractMonths() : dto.getContractMonths();
        if (total != null) {
            int first = LeaseTermRules.months(dto.getFirstPaymentMonths(), LeaseTermRules.months(lease.getFirstPaymentMonths(), 1));
            int cycle = LeaseTermRules.months(dto.getPaymentCycleMonths(), LeaseTermRules.months(lease.getPaymentCycleMonths(), 1));
            LeaseTermRules.validate(total, first, cycle);
            lease.setContractMonths(total);
            lease.setFirstPaymentMonths(first);
            lease.setPaymentCycleMonths(cycle);
            lease.setEndDate(LeaseTermRules.end(lease.getStartDate(), total));
        } else {
            if (dto.getFirstPaymentMonths() != null || dto.getPaymentCycleMonths() != null)
                throw new BusinessException(ErrorCode.INVALID_PARAM, "请先明确历史租约的合同总月数，不能推断付款安排");
            if (dto.getEndDate() != null) lease.setEndDate(dto.getEndDate());
            if (!lease.getEndDate().isAfter(lease.getStartDate()))
                throw new BusinessException(ErrorCode.INVALID_PARAM, "租期结束日期必须晚于开始日期");
        }
        lease.setMonthlyRent(dto.getMonthlyRent());
        lease.setDeposit(dto.getDeposit());
        lease.setContractUrl(dto.getContractUrl());
        lease.setRemark(dto.getRemark());
        leaseMapper.updateById(lease);
        changeLogService.logAttributeChanges(snapshot, lease, "管理方编辑租约属性");
        return toVO(lease);
    }

    /**
     * 管理方拟定新增合同段；居民同意且该段首笔租金成功后才延展，不直接覆盖原价与止期。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_RECORD", targetId = "#id", content = "'续租租住记录'")
    public LeaseVO renew(Long id, RenewLeaseDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        if (!"ACTIVE".equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "仅已生效租约可续租，当前状态：" + lease.getStatus());
        }
        if (dto.getNewEndDate()!=null && !dto.getNewEndDate().isAfter(lease.getEndDate())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "新结束日期必须晚于原结束日期");
        }
        termService.propose(id, dto);
        return toVO(lease);
    }

    /** 租约详情：RESIDENT 限本人 */
    public LeaseVO getById(Long id) {
        LeaseRecord lease = requireLease(id);
        checkReadAccess(lease);
        return toVO(lease);
    }

    public PageVO<LeaseVO> page(long page, long size, String status, Long residentId, Long houseId) {
        LambdaQueryWrapper<LeaseRecord> wrapper = new LambdaQueryWrapper<LeaseRecord>()
                .eq(StringUtils.hasText(status), LeaseRecord::getStatus, status)
                .eq(residentId != null, LeaseRecord::getTenantId, residentId)
                .eq(houseId != null, LeaseRecord::getHouseId, houseId)
                .orderByDesc(LeaseRecord::getId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            wrapper.eq(LeaseRecord::getTenantId, SecurityUtils.getUserId());
        }
        Page<LeaseRecord> result = leaseMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 状态流转：查表校验合法性；非法流转拒绝。TO_SIGN→ACTIVE 仅由协议完成后的首期成功驱动，
       须同步把房屋置为已入住（R66 v1.8：审核通过不再翻房态，生效动作才翻） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "LEASE_RECORD", targetId = "#id", content = "'租住状态变更为 ' + #dto.status")
    public void updateStatus(Long id, UpdateLeaseStatusDTO dto) {
        LeaseRecord lease = requireLease(id);
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        lockHouse(lease.getHouseId());
        lease = leaseMapper.selectByIdForUpdate(id);
        if (lease == null) throw new ResourceNotFoundException("租住记录不存在");
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        String current = lease.getStatus();
        String target = dto.getStatus();
        if (LeaseStatus.ACTIVE.equals(target) && !LeaseStatus.ACTIVE.equals(current)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "租约须协议确认且首期成功后生效，不能由管理员直接生效");
        }
        if (current.equals(target)) {
            return;
        }
        applyTransition(lease, target, dto.getRemark(), Boolean.TRUE.equals(dto.getConfirmDependents()));
        log.info("租住状态流转：leaseId={}, {} -> {}, operator={}",
                id, current, target, SecurityUtils.getUserId());
    }

    /**
     * 生效流转内核（状态机校验 + 持久化 + 变更留痕 + 房态联动）：状态流转端点与
     * 支付成功内核（PaymentService）共用，避免两处各写一份状态机判定导致口径漂移。
     * 同状态重复调用直接返回（幂等）；目标为 ACTIVE 时把房屋置为已入住。
     * 持久化为条件更新（OCR DEF-115）：WHERE 携带流转前状态——两路并发确认只成功一路；
     * 0 行时重读，已为目标状态按幂等处理直接返回（不重复留痕/房态联动），否则拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyTransition(LeaseRecord lease, String target, String reason) {
        applyTransition(lease,target,reason,false);
    }

    @Transactional(rollbackFor = Exception.class)
    public void applyTransition(LeaseRecord lease, String target, String reason, boolean confirmDependents) {
        String current = lease.getStatus();
        if (current.equals(target)) {
            return;
        }
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("租住状态不允许从 %s 流转到 %s", current, target));
        }
        if (LeaseStatus.ACTIVE.equals(target) && !"SIGNED".equals(lease.getAgreementStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "协议尚未确认完成，租约不可生效");
        }
        if (LeaseStatus.ACTIVE.equals(target)) {
            Long paid = paymentMapper.selectCount(new LambdaQueryWrapper<com.community.residence.payment.entity.LeasePayment>()
                .eq(com.community.residence.payment.entity.LeasePayment::getLeaseId, lease.getId())
                .eq(com.community.residence.payment.entity.LeasePayment::getPaymentType, "FIRST")
                .eq(com.community.residence.payment.entity.LeasePayment::getStatus, "SUCCESS"));
            if (paid == null || paid == 0) {
                throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "首期费用尚未确认成功，租约不可生效");
            }
            House house = lockHouse(lease.getHouseId());
            LeaseRecord fresh = leaseMapper.selectByIdForUpdate(lease.getId());
            if (fresh != null && LeaseStatus.ACTIVE.equals(fresh.getStatus())) return;
            if (fresh != null && !current.equals(fresh.getStatus())) {
                throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "租约状态已并发变更，不可生效");
            }
            if (house == null) throw new ResourceNotFoundException("租约房屋不存在，不能建立正式居住关系");
            if (!(HouseStatusConstant.VACANT.equals(house.getStatus()) || HouseStatusConstant.RESERVED.equals(house.getStatus()))) {
                throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已被占用，不能重复激活");
            }
            Long residents = relationMapper.selectCount(new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                .eq(com.community.residence.resident.entity.ResidenceRelation::getHouseId, lease.getHouseId())
                .isNull(com.community.residence.resident.entity.ResidenceRelation::getMoveOutDate));
            if (residents != null && residents > 0) {
                throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋已有在住关系，不能重复激活租约");
            }
        }
        lease.setStatus(target);
        if (StringUtils.hasText(reason)) {
            lease.setRemark(reason);
        }
        int rows = leaseMapper.update(lease, new LambdaUpdateWrapper<LeaseRecord>()
                .eq(LeaseRecord::getId, lease.getId())
                .eq(LeaseRecord::getStatus, current));
        if (rows == 0) {
            LeaseRecord fresh = leaseMapper.selectById(lease.getId());
            if (fresh != null && target.equals(fresh.getStatus())) {
                return;
            }
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    String.format("租住状态不允许从 %s 流转到 %s（并发变更，当前 %s）",
                            current, target, fresh == null ? "未知" : fresh.getStatus()));
        }
        changeLogService.logStatus(lease.getId(), current, target, reason);

        if (LeaseStatus.ACTIVE.equals(target)) {
            com.community.residence.resident.entity.ResidenceRelation relation = new com.community.residence.resident.entity.ResidenceRelation();
            relation.setLeaseId(lease.getId()); relation.setResidentId(lease.getTenantId());
            relation.setCommunityId(lease.getCommunityId()); relation.setHouseId(lease.getHouseId());
            relation.setRelationType("TENANT"); relation.setMoveInDate(lease.getStartDate()); relation.setIsPrimary(1);
            relationMapper.insert(relation);
            if(lease.getStartDate()!=null&&lease.getStartDate().isAfter(LocalDate.now())){
                House reserved=houseMapper.selectById(lease.getHouseId());reserved.setStatus(HouseStatusConstant.RESERVED);houseMapper.updateById(reserved);
            }else markHouseOccupied(lease.getHouseId());
            conversationService.sendSystemMessage(lease.getConversationId(), "协议已确认且首期费用支付成功，租约及正式居住关系已生效。");
        } else if (LeaseStatus.MOVED_OUT.equals(target)) {
            lockHouse(lease.getHouseId());
            endLeaseResidence(lease, confirmDependents);
            Long remaining = relationMapper.selectCount(new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                .eq(com.community.residence.resident.entity.ResidenceRelation::getHouseId, lease.getHouseId())
                .isNull(com.community.residence.resident.entity.ResidenceRelation::getMoveOutDate));
            House house = houseMapper.selectById(lease.getHouseId());
            if (house != null && (remaining == null || remaining == 0)) {
                house.setStatus(leaseMapper.selectBlockingIdsForUpdate(lease.getHouseId()).isEmpty()
                        ? HouseStatusConstant.VACANT : HouseStatusConstant.RESERVED);
                houseMapper.updateById(house);
            }
            conversationService.closeLeaseGroup(lease.getId());
        } else if (LeaseStatus.REJECTED.equals(target)) {
            agreementMapper.update(null, new LambdaUpdateWrapper<com.community.residence.agreement.entity.LeaseAgreement>()
                .eq(com.community.residence.agreement.entity.LeaseAgreement::getLeaseId, lease.getId())
                .in(com.community.residence.agreement.entity.LeaseAgreement::getStatus, "PENDING", "PARTIAL", "SIGNED")
                .set(com.community.residence.agreement.entity.LeaseAgreement::getStatus, "CANCELLED")
                .set(com.community.residence.agreement.entity.LeaseAgreement::getCancelReason,
                    org.springframework.util.StringUtils.hasText(reason) ? reason : "待签约租约已取消"));
            conversationService.closeLeaseGroup(lease.getId());
        }
    }

    /* 新关系精确关联租约；旧关系只在同居民/房屋候选唯一且未关联其他租约时兼容终止。 */
    private void endLeaseResidence(LeaseRecord lease, boolean confirmDependents) {
        List<com.community.residence.resident.entity.ResidenceRelation> candidates = relationMapper.selectList(
                new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                        .eq(com.community.residence.resident.entity.ResidenceRelation::getResidentId, lease.getTenantId())
                        .eq(com.community.residence.resident.entity.ResidenceRelation::getHouseId, lease.getHouseId())
                        .isNull(com.community.residence.resident.entity.ResidenceRelation::getMoveOutDate));
        for (var relation : candidates) {
            if (!lease.getId().equals(relation.getLeaseId())
                    && !(relation.getLeaseId() == null && candidates.size() == 1)) continue;
            residenceDependencyService.endMembers(relation.getId(), LocalDate.now(), confirmDependents);
            relation.setMoveOutDate(LocalDate.now());
            relationMapper.updateById(relation);
        }
    }

    /* 已在生效门禁锁定并校验真实房屋；缺失必须阻断整个成功事务，不能生成虚空关系。 */
    private void markHouseOccupied(Long houseId) {
        House house = houseMapper.selectById(houseId);
        if (house == null) {
            throw new ResourceNotFoundException("租约房屋不存在，不能建立正式居住关系");
        }
        house.setStatus(HouseStatusConstant.OCCUPIED);
        houseMapper.updateById(house);
    }

    /* 本地结算先锁房屋再锁租约，与申请/取消保持同序；渠道调用不持有这些锁。 */
    public LeaseRecord lockForSettlement(Long id) {
        LeaseRecord reference = requireLease(id);
        lockHouse(reference.getHouseId());
        LeaseRecord locked = leaseMapper.selectByIdForUpdate(id);
        if (locked == null) throw new ResourceNotFoundException("租约已不存在");
        return locked;
    }

    /** 租约变更历史（字段级前后值）：RESIDENT 限本人，ADMIN 限绑定社区 */
    public java.util.List<com.community.residence.lease.vo.LeaseChangeVO> changes(Long id) {
        LeaseRecord lease = requireLease(id);
        checkReadAccess(lease);
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        }
        return changeLogService.list(id);
    }

    /** 即将到期列表：ACTIVE 且结束日期在窗口内（到期提醒定时任务复用本查询语义） */
    public PageVO<LeaseVO> expiring(long page, long size, int days) {
        LocalDate today = LocalDate.now();
        LambdaQueryWrapper<LeaseRecord> wrapper = new LambdaQueryWrapper<LeaseRecord>()
                .eq(LeaseRecord::getStatus, "ACTIVE")
                .between(LeaseRecord::getEndDate, today, today.plusDays(days))
                .orderByAsc(LeaseRecord::getEndDate);
        Page<LeaseRecord> result = leaseMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 变更留痕用的字段快照：只拷贝参与 diff 的属性，避免误带主键参与比较 */
    private LeaseRecord snapshot(LeaseRecord lease) {
        LeaseRecord copy = new LeaseRecord();
        copy.setId(lease.getId());
        copy.setStartDate(lease.getStartDate());
        copy.setEndDate(lease.getEndDate());
        copy.setMonthlyRent(lease.getMonthlyRent());
        copy.setDeposit(lease.getDeposit());
        copy.setRemark(lease.getRemark());
        copy.setContractMonths(lease.getContractMonths());
        copy.setFirstPaymentMonths(lease.getFirstPaymentMonths());
        copy.setPaymentCycleMonths(lease.getPaymentCycleMonths());
        return copy;
    }

    public LeaseRecord requireLease(Long id) {
        LeaseRecord lease = leaseMapper.selectById(id);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        return lease;
    }

    private void checkReadAccess(LeaseRecord lease) {
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !lease.getTenantId().equals(SecurityUtils.getUserId())) {
            throw new ForbiddenException("无权查看他人租住记录");
        }
    }

    private LeaseVO toVO(LeaseRecord lease) {
        LeaseVO vo = LeaseVO.from(lease);
        Resident tenant = residentMapper.selectById(lease.getTenantId());
        if (tenant != null) {
            vo.setTenantName(tenant.getRealName());
        }
        House house = houseMapper.selectById(lease.getHouseId());
        if (house != null) {
            Unit unit = unitMapper.selectById(house.getUnitId());
            if (unit != null) {
                Building building = buildingMapper.selectById(unit.getBuildingId());
                String buildingName = building != null ? building.getName() : "";
                vo.setHouseLocation(buildingName + unit.getName() + house.getHouseNumber());
            }
            /* 社区名（居民端租约卡片按社区分组展示，R61 界面重设计补） */
            com.community.residence.community.entity.Community community =
                    communityMapper.selectById(house.getCommunityId());
            if (community != null) {
                vo.setCommunityName(community.getName());
            }
        }
        /* 到期标注（仅 ACTIVE 判定，架构设计 §6：非状态值） */
        if ("ACTIVE".equals(lease.getStatus())) {
            LocalDate today = LocalDate.now();
            if (lease.getEndDate().isBefore(today)) {
                vo.setExpiryFlag("EXPIRED");
            } else if (!lease.getEndDate().isAfter(today.plusDays(EXPIRING_WINDOW_DAYS))) {
                vo.setExpiryFlag("EXPIRING");
            }
        }
        return vo;
    }
}
