package com.community.residence.common.agreement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.agreement.dto.AgreementConfirmDTO;
import com.community.residence.common.agreement.dto.AgreementTemplateDTO;
import com.community.residence.common.agreement.dto.CreateAgreementDTO;
import com.community.residence.common.agreement.entity.AgreementTemplate;
import com.community.residence.common.agreement.entity.LeaseAgreement;
import com.community.residence.common.agreement.mapper.AgreementTemplateMapper;
import com.community.residence.common.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.common.agreement.vo.AgreementTemplateVO;
import com.community.residence.common.agreement.vo.LeaseAgreementVO;
import com.community.residence.common.auth.entity.SysAdminCommunity;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.conversation.constant.ConversationType;
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
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.conversation.mapper.ConversationMapper;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

/**
 * 租赁协议轻量版（R64）：管理方同意并发出正文快照，居民本人确认同意，首期成功后租约才生效。
 * 边界（2026-09-21 用户裁决）：平台自建，不引入第三方电子签与 CA 证书，
 * 因此确认记录是「平台内确认留痕」而非《电子签名法》意义的可靠电子签名；
 * 协议正文以快照入库，模板与租约后续变更均不回溯改写已生成协议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgreementService {

    /** 协议正文占位符 → 取值键的映射（中英文别名均支持，便于物业侧模板书写习惯） */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 未确认完成的状态集合：仅这些状态允许撤回/继续确认 */
    private static final Set<String> OPEN_STATUS = Set.of("PENDING", "PARTIAL");

    private final AgreementTemplateMapper templateMapper;
    private final LeaseAgreementMapper agreementMapper;
    private final LeaseRecordMapper leaseMapper;
    private final CommunityMapper communityMapper;
    private final HouseMapper houseMapper;
    private final UnitMapper unitMapper;
    private final BuildingMapper buildingMapper;
    private final ResidentMapper residentMapper;
    private final SysAdminCommunityMapper sysAdminCommunityMapper;
    private final SysUserMapper sysUserMapper;
    private final NotificationService notificationService;
    private final LeaseChangeLogService changeLogService;
    private final ConversationMapper conversationMapper;
    private final ConversationService conversationService;
    private final LeaseService leaseService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.community.residence.common.lease.service.LeaseContractTermService termService;

    /* ==================== 模板维护 ==================== */

    /** 模板分页：超管看全局 + 全部社区；社区管理员看全局 + 本社区（可见性在查询层显式约束） */
    public PageVO<AgreementTemplateVO> pageTemplates(long page, long size, Long communityId, String status) {
        LambdaQueryWrapper<AgreementTemplate> wrapper = new LambdaQueryWrapper<AgreementTemplate>()
                .eq(communityId != null, AgreementTemplate::getCommunityId, communityId)
                .eq(StringUtils.hasText(status), AgreementTemplate::getStatus, status);
        if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            Set<Long> bound = SecurityUtils.getCommunityIds();
            wrapper.and(w -> {
                w.isNull(AgreementTemplate::getCommunityId);
                if (bound != null && !bound.isEmpty()) {
                    w.or().in(AgreementTemplate::getCommunityId, bound);
                }
            });
        }
        wrapper.orderByDesc(AgreementTemplate::getIsDefault).orderByDesc(AgreementTemplate::getId);
        Page<AgreementTemplate> result = templateMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        PageVO<AgreementTemplateVO> vo = PageVO.of(result.convert(AgreementTemplateVO::from));
        vo.getRecords().forEach(this::fillTemplateCommunityName);
        return vo;
    }

    public AgreementTemplateVO getTemplate(Long id) {
        AgreementTemplate template = requireTemplate(id);
        checkTemplateAccess(template.getCommunityId());
        AgreementTemplateVO vo = AgreementTemplateVO.from(template);
        fillTemplateCommunityName(vo);
        return vo;
    }

    /**
     * 该租约可用模板清单：本社区模板 + 全局模板，默认模板排最前。
     * 供管理端「发起协议」弹窗选择，避免前端拿到跨社区模板。
     */
    public List<AgreementTemplateVO> availableTemplates(Long communityId) {
        List<AgreementTemplate> templates = templateMapper.selectList(new LambdaQueryWrapper<AgreementTemplate>()
                .eq(AgreementTemplate::getStatus, "ACTIVE")
                .and(w -> w.isNull(AgreementTemplate::getCommunityId)
                        .or().eq(AgreementTemplate::getCommunityId, communityId))
                .orderByDesc(AgreementTemplate::getIsDefault)
                .orderByDesc(AgreementTemplate::getId));
        List<AgreementTemplateVO> list = templates.stream().map(AgreementTemplateVO::from).toList();
        list.forEach(this::fillTemplateCommunityName);
        return list;
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE", targetType = "AGREEMENT_TEMPLATE", targetId = "#result.id", content = "'创建协议模板：' + #dto.name")
    public AgreementTemplateVO createTemplate(AgreementTemplateDTO dto) {
        checkTemplateWriteAccess(dto.getCommunityId());
        requireReadableContent(dto);
        AgreementTemplate template = new AgreementTemplate();
        applyTemplateDto(template, dto);
        template.setStatus("ACTIVE");
        templateMapper.insert(template);
        applyDefaultFlag(template, dto.getIsDefault());
        return AgreementTemplateVO.from(template);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "AGREEMENT_TEMPLATE", targetId = "#id", content = "'更新协议模板：' + #dto.name")
    public AgreementTemplateVO updateTemplate(Long id, AgreementTemplateDTO dto) {
        AgreementTemplate template = requireTemplate(id);
        checkTemplateAccess(template.getCommunityId());
        checkTemplateWriteAccess(dto.getCommunityId());
        requireReadableContent(dto);
        applyTemplateDto(template, dto);
        templateMapper.updateById(template);
        applyDefaultFlag(template, dto.getIsDefault());
        return AgreementTemplateVO.from(template);
    }

    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "DELETE", targetType = "AGREEMENT_TEMPLATE", targetId = "#id", content = "'删除协议模板'")
    public void deleteTemplate(Long id) {
        AgreementTemplate template = requireTemplate(id);
        checkTemplateAccess(template.getCommunityId());
        /* 已生成协议保存正文快照，模板删除不影响历史协议；此处仅删模板本体 */
        templateMapper.deleteById(id);
        log.info("协议模板已删除：templateId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /** 启用/停用模板（停用后不再出现在「发起协议」可选清单） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "AGREEMENT_TEMPLATE", targetId = "#id", content = "'协议模板状态变更为 ' + #status")
    public AgreementTemplateVO updateTemplateStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "模板状态只支持 ACTIVE / INACTIVE");
        }
        AgreementTemplate template = requireTemplate(id);
        checkTemplateAccess(template.getCommunityId());
        template.setStatus(status);
        templateMapper.updateById(template);
        return AgreementTemplateVO.from(template);
    }

    /* ==================== 协议发起与签署 ==================== */

    /**
     * 发起协议：按模板渲染正文快照入库，租约标记为待确认，并通知居民。
     * 同一租约同时只允许一份未完成协议，已有待确认/单方已确认协议时拒绝重复发起。
     */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE", targetType = "LEASE_AGREEMENT", targetId = "#result.id", content = "'发起租约协议'")
    public LeaseAgreementVO createAgreement(CreateAgreementDTO dto) {
        requireManager();
        LeaseRecord lease = lockLease(dto.getLeaseId());
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        if (!LeaseStatus.TO_SIGN.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅待签约租约可发出协议");
        }
        Long openCount = agreementMapper.selectCount(new LambdaQueryWrapper<LeaseAgreement>()
                .eq(LeaseAgreement::getLeaseId, lease.getId())
                .in(LeaseAgreement::getStatus, OPEN_STATUS));
        if (openCount > 0) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "该租约已有待确认协议，请先撤回后再发起");
        }

        AgreementTemplate template = dto.getTemplateId() != null
                ? requireTemplate(dto.getTemplateId())
                : findDefaultTemplate(lease.getCommunityId());
        if (template == null) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "未找到可用协议模板，请先在协议模板中配置并设为默认");
        }
        if (template.getCommunityId() != null && !template.getCommunityId().equals(lease.getCommunityId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "模板不属于该租约所在社区");
        }

        if (!"ACTIVE".equals(template.getStatus())) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "协议模板已停用，请选择启用的模板");
        }

        String houseLocation = houseLocation(lease);
        LeaseAgreement agreement = new LeaseAgreement();
        agreement.setLeaseId(lease.getId());
        agreement.setCommunityId(lease.getCommunityId());
        agreement.setTemplateId(template.getId());
        agreement.setTemplateName(template.getName());
        agreement.setTitle(StringUtils.hasText(dto.getTitle())
                ? dto.getTitle()
                : houseLocation + " 租赁协议");
        var contractTerm=termService.initial(lease);
        agreement.setContent(render(template.getContent(), lease, houseLocation) + "\n\n" + contractTerm.getContentSnapshot());
        agreement.setTemplateFileUrl(template.getFileUrl());
        agreement.setTemplateFileName(template.getFileName());
        agreement.setStatus("PARTIAL");
        agreement.setAdminId(SecurityUtils.getUserId());
        agreement.setAdminConfirmName(resolveConfirmName());
        agreement.setAdminConfirmTime(LocalDateTime.now());
        agreement.setTenantId(lease.getTenantId());
        agreement.setRemark(dto.getRemark());
        agreement.setCreatedBy(SecurityUtils.getUserId());
        agreementMapper.insert(agreement);

        markLeaseAgreementStatus(lease, "PARTIAL");
        changeLogService.logAgreement(lease.getId(), "管理方同意并发出协议", agreement.getTitle());
        notificationService.createForResident(lease.getTenantId(), lease.getCommunityId(),
                "待确认租赁协议", "您的租约（" + houseLocation + "）已生成租赁协议，请在「我的租约」中查看并确认",
                "LEASE", "LEASE_AGREEMENT", agreement.getId());
        conversationService.sendAgreementMessage(lease.getConversationId(), agreement.getId(), "管理方已同意并发出协议「"+agreement.getTitle()+"」，请居民查看该协议正文后决定是否同意。");
        log.info("租约协议已发起：agreementId={}, leaseId={}, templateId={}, operator={}",
                agreement.getId(), lease.getId(), template.getId(), SecurityUtils.getUserId());
        return LeaseAgreementVO.from(agreement);
    }

    /* 居民仅对当前协议快照确认；锁定后重读防止撤回/重发竞争，已完成重试不改首次留痕。 */
    @Transactional(rollbackFor = Exception.class)
    public LeaseAgreementVO confirm(Long id, AgreementConfirmDTO dto) {
        LeaseAgreement agreement = requireAgreement(id);
        requireTenant(agreement);
        LeaseRecord lease = lockLease(agreement.getLeaseId());
        agreement = lockAgreement(id);
        requireTenant(agreement);
        if (!SecurityUtils.getUserId().equals(lease.getTenantId())) {
            throw new ForbiddenException("无权确认他人租约");
        }
        if ("SIGNED".equals(agreement.getStatus())) {
            return LeaseAgreementVO.from(agreement);
        }
        if (!OPEN_STATUS.contains(agreement.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "当前协议已撤回或不可确认");
        }
        if (agreement.getAdminConfirmTime() == null) {
            throw new BusinessException(ErrorCode.OPERATION_FAILED, "旧协议缺少管理方同意记录，请联系管理员撤回后重新发出");
        }
        if (!LeaseStatus.TO_SIGN.equals(lease.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅待签约租约可确认协议");
        }
        String operatorName = resolveConfirmName();
        agreement.setTenantConfirmTime(LocalDateTime.now());
        agreement.setTenantConfirmName(operatorName);
        if (StringUtils.hasText(dto.getRemark())) {
            agreement.setRemark(dto.getRemark());
        }
        agreement.setStatus("SIGNED");
        agreementMapper.updateById(agreement);
        termService.signInitial(lease);
        markLeaseAgreementStatus(lease, "SIGNED");
        changeLogService.logAgreement(lease.getId(), "居民同意协议，双方确认完成",
                agreement.getTitle() + "（" + operatorName + "）");
        notifyCounterpart(agreement, lease);
        conversationService.sendAgreementMessage(lease.getConversationId(), agreement.getId(),
                "租赁协议双方已确认，房屋继续保留；首期费用支付成功后租约及正式居住关系才生效。");
        log.info("居民确认协议：agreementId={}, operator={}", id, operatorName);
        return LeaseAgreementVO.from(agreement);
    }

    /** 撤回协议（管理方）：仅未完成协议可撤回，租约签约状态回落为未发起 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "LEASE_AGREEMENT", targetId = "#id", content = "'撤回租约协议：' + #reason")
    public void cancel(Long id, String reason) {
        requireManager();
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "撤回原因不能为空");
        }
        LeaseAgreement agreement = requireAgreement(id);
        SecurityUtils.checkCommunityAccess(agreement.getCommunityId());
        LeaseRecord lease = lockLease(agreement.getLeaseId());
        agreement = lockAgreement(id);
        SecurityUtils.checkCommunityAccess(agreement.getCommunityId());
        if ("CANCELLED".equals(agreement.getStatus())) return;
        if (!OPEN_STATUS.contains(agreement.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,
                    "仅待确认或单方已确认的协议可撤回，当前状态：" + agreement.getStatus());
        }
        agreement.setStatus("CANCELLED");
        agreement.setCancelReason(reason);
        agreementMapper.updateById(agreement);
        conversationService.sendAgreementMessage(lease.getConversationId(),agreement.getId(),"管理方已撤回协议「"+agreement.getTitle()+"」，该快照仅供历史查看。");
        termService.cancelInitial(lease.getId());
        markLeaseAgreementStatus(lease, "NONE");
        changeLogService.logAgreement(lease.getId(), "撤回协议", reason);
        notificationService.createForResident(lease.getTenantId(), lease.getCommunityId(),
                "租赁协议已撤回", "您的租约协议已被管理方撤回：" + reason, "LEASE", "LEASE_AGREEMENT", id);
        log.info("租约协议已撤回：agreementId={}, reason={}, operator={}", id, reason, SecurityUtils.getUserId());
    }

    /** 某租约的协议流水（含已撤回历史，按发起时间倒序） */
    public List<LeaseAgreementVO> listByLease(Long leaseId) {
        LeaseRecord lease = requireLease(leaseId);
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !SecurityUtils.getUserId().equals(lease.getTenantId())) {
            throw new ForbiddenException("无权查看他人租约协议");
        }
        SecurityUtils.checkCommunityAccess(lease.getCommunityId());
        return agreementMapper.selectList(new LambdaQueryWrapper<LeaseAgreement>()
                        .eq(LeaseAgreement::getLeaseId, leaseId)
                        .orderByDesc(LeaseAgreement::getId))
                .stream().map(LeaseAgreementVO::from).toList();
    }

    public LeaseAgreementVO getById(Long id) {
        LeaseAgreement agreement = requireAgreement(id);
        LeaseRecord lease = requireLease(agreement.getLeaseId());
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)
                && !SecurityUtils.getUserId().equals(agreement.getTenantId())) {
            throw new ForbiddenException("无权查看他人协议");
        }
        SecurityUtils.checkCommunityAccess(agreement.getCommunityId());
        return LeaseAgreementVO.from(agreement);
    }

    /* ==================== 内部工具 ==================== */

    /* 正文占位符渲染：中英文别名同义，未提供的值渲染为「—」避免正文出现裸占位符 */
    private String render(String templateContent, LeaseRecord lease, String houseLocation) {
        if (!StringUtils.hasText(templateContent)) {
            return null;
        }
        House house = houseMapper.selectById(lease.getHouseId());
        Community community = communityMapper.selectById(lease.getCommunityId());
        Resident tenant = residentMapper.selectById(lease.getTenantId());
        String text = templateContent;
        text = replace(text, community != null ? community.getName() : null, "社区名称", "communityName");
        text = replace(text, houseLocation, "房屋位置", "houseLocation");
        text = replace(text, house != null ? house.getHouseNumber() : null, "房号", "houseNumber");
        text = replace(text, house != null ? String.valueOf(house.getFloor()) : null, "楼层", "floor");
        text = replace(text, tenant != null ? tenant.getRealName() : null, "租客姓名", "tenantName");
        text = replace(text, lease.getStartDate() != null ? lease.getStartDate().format(DATE_FMT) : null,
                "租期开始", "startDate");
        text = replace(text, lease.getEndDate() != null ? lease.getEndDate().format(DATE_FMT) : null,
                "租期结束", "endDate");
        text = replace(text, money(lease.getMonthlyRent()), "月租金", "monthlyRent");
        text = replace(text, money(lease.getDeposit()), "押金", "deposit");
        text = replace(text, String.valueOf(lease.getId()), "租约编号", "leaseId");
        text = replace(text, LocalDate.now().format(DATE_FMT), "签约日期", "signDate");
        return text;
    }

    private String replace(String text, String value, String... keys) {
        String replacement = StringUtils.hasText(value) ? value : "—";
        String result = text;
        for (String key : keys) {
            result = result.replace("{{" + key + "}}", replacement)
                    .replace("{{ " + key + " }}", replacement);
        }
        return result;
    }

    private String money(BigDecimal value) {
        return value == null ? null : "¥" + value.stripTrailingZeros().toPlainString();
    }

    private String houseLocation(LeaseRecord lease) {
        House house = houseMapper.selectById(lease.getHouseId());
        if (house == null) {
            return "房屋 " + lease.getHouseId();
        }
        Unit unit = unitMapper.selectById(house.getUnitId());
        if (unit == null) {
            return house.getHouseNumber();
        }
        Building building = buildingMapper.selectById(unit.getBuildingId());
        return (building != null ? building.getName() : "") + unit.getName() + house.getHouseNumber();
    }

    /* 协议确认通知：居民与社区绑定启用管理员均使用各自身份空间收件；
       管理方通知不能依赖页面待办刷新，否则居民先确认后管理端无法收到状态事件。 */
    private void notifyCounterpart(LeaseAgreement agreement, LeaseRecord lease) {
        String location = houseLocation(lease);
        notificationService.createForResident(lease.getTenantId(), lease.getCommunityId(),
                "租赁协议已确认", "您的租赁协议（" + location + "）双方均已同意，房屋继续保留，首期费用支付成功后租约才生效",
                "LEASE", "LEASE_AGREEMENT", agreement.getId());
        notifyCommunityAdmins(lease, "租赁协议已确认",
                "租约 #" + lease.getId() + "（" + location + "）居民已同意协议，待首期费用支付成功后正式生效", agreement.getId());
    }

    /* 协议事件的管理方收件人：与承租确认、到期提醒共用社区绑定管理员口径，
       只投递启用的 ADMIN，不把 SUPER_ADMIN 混入社区管理员身份集合。 */
    private void notifyCommunityAdmins(LeaseRecord lease, String title, String content, Long agreementId) {
        List<SysAdminCommunity> bindings = sysAdminCommunityMapper.selectList(
                new LambdaQueryWrapper<SysAdminCommunity>()
                        .eq(SysAdminCommunity::getCommunityId, lease.getCommunityId()));
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        List<Long> adminIds = bindings.stream()
                .map(SysAdminCommunity::getAdminId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (adminIds.isEmpty()) {
            return;
        }
        List<SysUser> admins = sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .in(SysUser::getId, adminIds)
                        .eq(SysUser::getRole, RoleConstants.ADMIN)
                        .eq(SysUser::getStatus, "ACTIVE"));
        if (admins == null) {
            return;
        }
        for (SysUser admin : admins) {
            notificationService.createForUser(admin.getId(), lease.getCommunityId(), title, content,
                    "LEASE", "LEASE_AGREEMENT", agreementId);
        }
    }

    /* 租约签约状态与协议状态同义，协议完成后回写便于租约列表直接展示 */
    private void markLeaseAgreementStatus(LeaseRecord lease, String status) {
        lease.setAgreementStatus(status);
        leaseMapper.updateById(lease);
    }

    private AgreementTemplate findDefaultTemplate(Long communityId) {
        AgreementTemplate preferred = templateMapper.selectOne(new LambdaQueryWrapper<AgreementTemplate>()
                .eq(AgreementTemplate::getStatus, "ACTIVE")
                .eq(AgreementTemplate::getIsDefault, 1)
                .eq(AgreementTemplate::getCommunityId, communityId)
                .orderByDesc(AgreementTemplate::getId)
                .last("LIMIT 1"));
        if (preferred != null) {
            return preferred;
        }
        return templateMapper.selectOne(new LambdaQueryWrapper<AgreementTemplate>()
                .eq(AgreementTemplate::getStatus, "ACTIVE")
                .eq(AgreementTemplate::getIsDefault, 1)
                .isNull(AgreementTemplate::getCommunityId)
                .orderByDesc(AgreementTemplate::getId)
                .last("LIMIT 1"));
    }

    /* 模板正文与附件至少要有一项，否则协议无实质内容可确认 */
    private void requireReadableContent(AgreementTemplateDTO dto) {
        if (!StringUtils.hasText(dto.getContent()) && !StringUtils.hasText(dto.getFileUrl())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "协议正文与模板附件至少填写一项");
        }
    }

    private void applyTemplateDto(AgreementTemplate template, AgreementTemplateDTO dto) {
        template.setCommunityId(dto.getCommunityId());
        template.setName(dto.getName());
        template.setContent(dto.getContent());
        template.setFileName(dto.getFileName());
        template.setFileUrl(dto.getFileUrl());
        template.setFileSize(dto.getFileSize());
        template.setRemark(dto.getRemark());
    }

    /* 默认模板同范围内唯一：设置新默认时清除同社区（含全局）范围内其他默认标记 */
    private void applyDefaultFlag(AgreementTemplate template, Integer isDefault) {
        if (isDefault == null || isDefault != 1) {
            if (template.getIsDefault() == null) {
                template.setIsDefault(0);
                templateMapper.updateById(template);
            }
            return;
        }
        LambdaUpdateWrapper<AgreementTemplate> clear = new LambdaUpdateWrapper<AgreementTemplate>()
                .set(AgreementTemplate::getIsDefault, 0)
                .eq(AgreementTemplate::getIsDefault, 1)
                .ne(AgreementTemplate::getId, template.getId());
        if (template.getCommunityId() == null) {
            clear.isNull(AgreementTemplate::getCommunityId);
        } else {
            clear.eq(AgreementTemplate::getCommunityId, template.getCommunityId());
        }
        templateMapper.update(null, clear);
        template.setIsDefault(1);
        templateMapper.updateById(template);
    }

    /* 全局模板仅超管可维护；社区模板限本社区管理员（超管放行） */
    private void checkTemplateWriteAccess(Long communityId) {
        if (communityId == null) {
            if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
                throw new ForbiddenException("全局通用模板仅超级管理员可维护");
            }
            return;
        }
        SecurityUtils.checkCommunityAccess(communityId);
    }

    private void checkTemplateAccess(Long communityId) {
        if (communityId == null) {
            return;
        }
        SecurityUtils.checkCommunityAccess(communityId);
    }

    private void fillTemplateCommunityName(AgreementTemplateVO vo) {
        if (vo.getCommunityId() == null) {
            vo.setCommunityName("全局通用模板");
            return;
        }
        Community community = communityMapper.selectById(vo.getCommunityId());
        vo.setCommunityName(community != null ? community.getName() : null);
    }

    /* 确认人仅取当前账号，不信任客户端代填姓名。 */
    private String resolveConfirmName() {
        Long userId = SecurityUtils.getUserId();
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            Resident resident = residentMapper.selectById(userId);
            if (resident != null && StringUtils.hasText(resident.getRealName())) {
                return resident.getRealName();
            }
            return resident != null && StringUtils.hasText(resident.getUsername())
                    ? resident.getUsername() : SecurityUtils.getUser().getUsername();
        }
        SysUser sysUser = sysUserMapper.selectById(userId);
        if (sysUser != null) {
            return StringUtils.hasText(sysUser.getRealName()) ? sysUser.getRealName() : sysUser.getUsername();
        }
        return SecurityUtils.getUser() != null ? SecurityUtils.getUser().getUsername() : "未知";
    }

    private void requireManager() {
        if (!SecurityUtils.hasRole(RoleConstants.ADMIN) && !SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            throw new ForbiddenException("仅管理方可同意并发出或撤回协议");
        }
    }

    private void requireTenant(LeaseAgreement agreement) {
        if (!SecurityUtils.hasRole(RoleConstants.RESIDENT)
                || !SecurityUtils.getUserId().equals(agreement.getTenantId())) {
            throw new ForbiddenException("仅协议居民本人可确认同意");
        }
    }

    private LeaseRecord lockLease(Long id) {
        LeaseRecord lease = leaseMapper.selectByIdForUpdate(id);
        if (lease == null) throw new ResourceNotFoundException("租住记录不存在");
        return lease;
    }

    private LeaseAgreement lockAgreement(Long id) {
        LeaseAgreement agreement = agreementMapper.selectByIdForUpdate(id);
        if (agreement == null) throw new ResourceNotFoundException("租约协议不存在");
        return agreement;
    }

    private AgreementTemplate requireTemplate(Long id) {
        AgreementTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new ResourceNotFoundException("协议模板不存在");
        }
        return template;
    }

    private LeaseAgreement requireAgreement(Long id) {
        LeaseAgreement agreement = agreementMapper.selectById(id);
        if (agreement == null) {
            throw new ResourceNotFoundException("租约协议不存在");
        }
        return agreement;
    }

    private LeaseRecord requireLease(Long id) {
        LeaseRecord lease = leaseMapper.selectById(id);
        if (lease == null) {
            throw new ResourceNotFoundException("租住记录不存在");
        }
        return lease;
    }
}
