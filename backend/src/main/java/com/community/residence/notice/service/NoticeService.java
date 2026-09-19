package com.community.residence.notice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.NoticeStatus;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.service.CommunityService;
import com.community.residence.notice.dto.CreateNoticeDTO;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.entity.NoticeViewRecord;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.notice.vo.NoticeVO;
import com.community.residence.notice.vo.NoticeViewRecordVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公告业务逻辑：DRAFT → PUBLISHED → WITHDRAWN 状态机；
 * notice 表无 community_id 列（拦截器跳过），ADMIN 数据范围由
 * notice_target（COMMUNITY 目标）在业务层校验；无目标记录为全系统广播（仅超管管理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    /** 失效时间默认值：发布时间 + 30 天 */
    private static final long DEFAULT_VALID_DAYS = 30;

    /** 广播公告（无定向目标）过滤子查询：notice_target 无记录即广播 */
    private static final String BROADCAST_FILTER_SQL =
            "NOT EXISTS (SELECT 1 FROM notice_target nt WHERE nt.notice_id = notice.id)";

    /**
     * 游客可见过滤（DEF-046）：无任何 COMMUNITY/BUILDING 定向行（纯广播）
     * 或存在 GUEST 目标行（管理端显式开放游客，如使用教程）；社区/楼栋定向
     * 公告对游客不可见。GUEST 行本身不影响"广播"判定（纯 GUEST 公告两类口径均命中）。
     */
    private static final String GUEST_VISIBLE_FILTER_SQL =
            "(NOT EXISTS (SELECT 1 FROM notice_target nt WHERE nt.notice_id = notice.id "
                    + "AND nt.target_type IN ('COMMUNITY', 'BUILDING')) "
                    + "OR EXISTS (SELECT 1 FROM notice_target nt WHERE nt.notice_id = notice.id "
                    + "AND nt.target_type = 'GUEST'))";

    private final NoticeMapper noticeMapper;
    private final NoticeTargetMapper noticeTargetMapper;
    private final NoticeViewRecordMapper viewRecordMapper;
    private final CommunityService communityService;
    private final SysUserMapper sysUserMapper;
    private final ResidentMapper residentMapper;
    private final NotificationService notificationService;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final com.community.residence.community.mapper.BuildingMapper buildingMapper;

    /* 创建公告：初始 DRAFT；多社区/楼栋定向逐项写 notice_target（R25 v1.2），
       ADMIN 逐项目标校验绑定范围；无目标为全系统广播（仅超管） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "CREATE", targetType = "NOTICE", targetId = "#result.id", content = "'创建公告：' + #dto.title")
    public NoticeVO create(CreateNoticeDTO dto) {
        List<NoticeServiceTarget> targets = resolveTargets(dto);
        if (targets.isEmpty()) {
            if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "社区管理员必须指定目标社区");
            }
        }

        Notice notice = new Notice();
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setPublisherId(SecurityUtils.getUserId());
        notice.setStatus(NoticeStatus.DRAFT);
        notice.setPublishTime(dto.getPublishTime());
        notice.setEndTime(resolveEndTime(dto));
        notice.setViewCount(0);
        notice.setIsPinned(dto.getIsPinned() != null && dto.getIsPinned() == 1 ? 1 : 0);
        notice.setPriority(StringUtils.hasText(dto.getPriority()) ? dto.getPriority() : "NORMAL");
        notice.setType(StringUtils.hasText(dto.getType()) ? dto.getType() : "ANNOUNCEMENT");
        noticeMapper.insert(notice);

        for (NoticeServiceTarget target : targets) {
            NoticeTarget entity = new NoticeTarget();
            entity.setNoticeId(notice.getId());
            entity.setTargetType(target.type());
            /* GUEST 目标无对应实体，target_id 列 NOT NULL 固定落 0（V13 注释口径） */
            entity.setTargetId(target.id() != null ? target.id() : 0L);
            noticeTargetMapper.insert(entity);
        }
        return toVO(notice);
    }

    /* 更新公告：仅 DRAFT 可改；目标范围随编辑重写（DEF-043：先删后插对齐 create 落库模式） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "UPDATE", targetType = "NOTICE", targetId = "#id", content = "'更新公告：' + #dto.title")
    public NoticeVO update(Long id, CreateNoticeDTO dto) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.DRAFT.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅草稿状态的公告可修改");
        }
        List<NoticeServiceTarget> targets = resolveTargets(dto);
        if (targets.isEmpty()) {
            if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "社区管理员必须指定目标社区");
            }
        }
        for (NoticeServiceTarget target : targets) {
            checkTargetAccess(target);
        }
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setPublishTime(dto.getPublishTime());
        notice.setEndTime(resolveEndTime(dto));
        notice.setIsPinned(dto.getIsPinned() != null && dto.getIsPinned() == 1 ? 1 : 0);
        notice.setPriority(StringUtils.hasText(dto.getPriority()) ? dto.getPriority() : "NORMAL");
        notice.setType(StringUtils.hasText(dto.getType()) ? dto.getType() : "ANNOUNCEMENT");
        noticeMapper.updateById(notice);
        /* 目标重写（DEF-043）：先删后插——编辑定向生效，与 create 落库模式一致；
           超管改为广播（targets 空）时清空全部目标行 */
        noticeTargetMapper.delete(new LambdaQueryWrapper<NoticeTarget>()
                .eq(NoticeTarget::getNoticeId, id));
        for (NoticeServiceTarget target : targets) {
            NoticeTarget entity = new NoticeTarget();
            entity.setNoticeId(id);
            entity.setTargetType(target.type());
            /* GUEST 目标无对应实体，target_id 列 NOT NULL 固定落 0（V13 注释口径） */
            entity.setTargetId(target.id() != null ? target.id() : 0L);
            noticeTargetMapper.insert(entity);
        }
        return toVO(notice);
    }

    /* 失效时间解析：endTime 优先，其次前端表单字段名 expireTime（V12 接收映射），
       均空缺省发布时间 + 30 天 */
    private LocalDateTime resolveEndTime(CreateNoticeDTO dto) {
        if (dto.getEndTime() != null) {
            return dto.getEndTime();
        }
        if (dto.getExpireTime() != null) {
            return dto.getExpireTime();
        }
        return dto.getPublishTime().plusDays(DEFAULT_VALID_DAYS);
    }

    /** 解析目标列表：targets 列表优先；否则回退 communityId 单目标（向后兼容）；均空=广播 */
    private List<NoticeServiceTarget> resolveTargets(CreateNoticeDTO dto) {
        if (dto.getTargets() != null && !dto.getTargets().isEmpty()) {
            return dto.getTargets().stream()
                    .map(t -> new NoticeServiceTarget(t.getTargetType(), t.getTargetId()))
                    .peek(this::checkTargetAccess)
                    .toList();
        }
        if (dto.getCommunityId() != null) {
            /* communityId 单目标写法与 targets 列表同口径校验（DEF-004：原仅 targets 分支校验） */
            NoticeServiceTarget target = new NoticeServiceTarget("COMMUNITY", dto.getCommunityId());
            checkTargetAccess(target);
            return List.of(target);
        }
        return List.of();
    }

    /* 逐项目标校验：类型合法（COMMUNITY/BUILDING/GUEST）、社区运营中、ADMIN 限绑定范围（R24 v1.2）。
       GUEST（DEF-046 游客可见）属全局级操作仅超管（定义 v1.3 权责：全平台游客触达），
       ADMIN 设置 403 拒绝并留应用日志 */
    private void checkTargetAccess(NoticeServiceTarget target) {
        if ("GUEST".equals(target.type())) {
            if (target.id() != null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "GUEST 目标不携带目标ID");
            }
            if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
                log.warn("拒绝非超管设置游客可见公告目标：operatorId={}", SecurityUtils.getUserId());
                throw new ForbiddenException("游客可见目标仅超级管理员可设置");
            }
            return;
        }
        if (!"COMMUNITY".equals(target.type()) && !"BUILDING".equals(target.type())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "非法目标类型：" + target.type());
        }
        if (target.id() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "目标ID不能为空");
        }
        if ("COMMUNITY".equals(target.type())) {
            communityService.requireActiveCommunity(target.id());
            SecurityUtils.checkCommunityAccess(target.id());
        } else {
            /* BUILDING 目标：经楼栋归属社区校验（building.community_id） */
            com.community.residence.community.entity.Building building =
                    buildingMapper.selectById(target.id());
            if (building == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "目标楼栋不存在");
            }
            SecurityUtils.checkCommunityAccess(building.getCommunityId());
        }
    }

    /* 目标范围值对象 */
    private record NoticeServiceTarget(String type, Long id) {
    }

    /* 删除公告：仅 DRAFT/WITHDRAWN 可删（已发布公告走撤回流程）；
       子表 FK 为 RESTRICT，须先清两张子表（定向目标 + 浏览回执）再删父表 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "DELETE", targetType = "NOTICE", targetId = "#id")
    public void delete(Long id) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.DRAFT.equals(notice.getStatus())
                && !NoticeStatus.WITHDRAWN.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅草稿或已撤回的公告可删除");
        }
        noticeTargetMapper.delete(new LambdaQueryWrapper<NoticeTarget>()
                .eq(NoticeTarget::getNoticeId, id));
        viewRecordMapper.delete(new LambdaQueryWrapper<NoticeViewRecord>()
                .eq(NoticeViewRecord::getNoticeId, id));
        noticeMapper.deleteById(id);
        log.info("公告已删除：noticeId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /**
     * 公告详情：普通访问仅 PUBLISHED 且未过期；
     * 居民限本人社区定向公告与全系统广播（R25 范围外不可见，DEF-003）；
     * 游客/未登录限纯广播 + GUEST 显式可见（DEF-046，与列表同口径）；
     * ADMIN 越绑定社区按数据不可见 404（与写路径 403 区分口径）。
     */
    public NoticeVO getById(Long id) {
        Notice notice = requireNotice(id);
        if (SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            return toVO(notice);
        }
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            /* 数据级读过滤：任一目标（社区/楼栋归属社区）落在绑定集合内才可见 */
            if (resolveNoticeCommunityIds(notice.getId()).stream()
                    .noneMatch(SecurityUtils.getCommunityIds()::contains)) {
                throw new ResourceNotFoundException("公告不存在");
            }
            return toVO(notice);
        }
        if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            if (!NoticeStatus.PUBLISHED.equals(notice.getStatus()) || isExpired(notice)
                    || !visibleToResidentCommunities(notice, residentCommunityIds(SecurityUtils.getUserId()))) {
                throw new ResourceNotFoundException("公告不存在或已下线");
            }
        } else if (!NoticeStatus.PUBLISHED.equals(notice.getStatus()) || isExpired(notice)
                || !visibleToGuest(notice.getId())) {
            /* 游客/未登录（DEF-046）：公开口径加可见性维度（纯广播 OR GUEST 目标） */
            throw new ResourceNotFoundException("公告不存在或已下线");
        }
        return toVO(notice);
    }

    /**
     * 公告分页列表：匿名/普通用户仅 PUBLISHED 且未过期，游客另限纯广播 +
     * GUEST 显式可见（DEF-046）；居民限本人社区定向 + 全系统广播（DEF-003）；
     * 管理端角色返回全部状态（ADMIN 限绑定社区，业务层经 notice_target 过滤）。
     */
    public PageVO<NoticeVO> page(long page, long size, Long communityId, String keyword,
                                 String priority, Integer isPinned, String type) {
        boolean managerView = SecurityUtils.hasRole(RoleConstants.ADMIN)
                || SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN);

        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Notice::getTitle, keyword)
                        .or().like(Notice::getContent, keyword))
                /* DEF-031：priority/isPinned/type 过滤参数（R25 置顶配套 + V12 字段） */
                .eq(StringUtils.hasText(priority), Notice::getPriority, priority)
                .eq(isPinned != null, Notice::getIsPinned, isPinned)
                .eq(StringUtils.hasText(type), Notice::getType, type)
                /* 置顶排最前（R25 v1.2），同档按发布时间倒序 */
                .orderByDesc(Notice::getIsPinned)
                .orderByDesc(Notice::getPublishTime);

        if (!managerView) {
            wrapper.eq(Notice::getStatus, NoticeStatus.PUBLISHED)
                    .and(w -> w.isNull(Notice::getEndTime)
                            .or().ge(Notice::getEndTime, LocalDateTime.now()));
        }
        if (communityId != null) {
            wrapper.in(Notice::getId, resolveNoticeIdsByCommunity(communityId, wrapper));
        }
        /* ADMIN 限绑定社区公告（经 notice_target 过滤）；SUPER_ADMIN 不过滤 */
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            List<Long> boundNoticeIds = noticeTargetMapper.selectList(
                            new LambdaQueryWrapper<NoticeTarget>()
                                    .eq(NoticeTarget::getTargetType, "COMMUNITY")
                                    .in(NoticeTarget::getTargetId, SecurityUtils.getCommunityIds()))
                    .stream().map(NoticeTarget::getNoticeId).toList();
            if (boundNoticeIds.isEmpty()) {
                return PageVO.of(List.of(), 0, page, size);
            }
            wrapper.in(Notice::getId, boundNoticeIds);
        } else if (SecurityUtils.hasRole(RoleConstants.RESIDENT)) {
            /* 居民限本人社区定向公告 + 全系统广播（R25 范围外不可见，DEF-003；
               游客/未登录维持公开口径不动，未被缺陷报告覆盖） */
            List<Long> mine = residentCommunityIds(SecurityUtils.getUserId());
            List<Long> visibleIds = targetedNoticeIdsForCommunities(mine);
            if (visibleIds.isEmpty()) {
                wrapper.apply(BROADCAST_FILTER_SQL);
            } else {
                wrapper.and(w -> w.in(Notice::getId, visibleIds)
                        .or().apply(BROADCAST_FILTER_SQL));
            }
        } else {
            /* 游客/匿名（DEF-046）：仅纯广播 + 显式开放游客（GUEST 目标）的公告，
               社区/楼栋定向公告不可见（原全量 PUBLISHED 口径过宽，用户手测裁决） */
            wrapper.apply(GUEST_VISIBLE_FILTER_SQL);
        }

        Page<Notice> result = noticeMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(this::toVO));
    }

    /* 发布公告：DRAFT → PUBLISHED；publishTime 早于当前时间立即生效；
       发布触达目标居民（R48 公告广播通知），渠道留痕由 NotificationService
       按 notify.channel.levels 配置分级触发（R51） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "NOTICE", targetId = "#id", content = "'发布公告'")
    public void publish(Long id, LocalDateTime publishTime) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.DRAFT.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅草稿状态的公告可发布");
        }
        notice.setStatus(NoticeStatus.PUBLISHED);
        notice.setPublishTime(publishTime);
        notice.setStartTime(publishTime);
        noticeMapper.updateById(notice);

        for (Long residentId : noticeTargetResidentIds(notice.getId())) {
            notificationService.create(residentId, null, "社区公告",
                    "新公告「" + notice.getTitle() + "」已发布，请查看",
                    "NOTICE", "NOTICE", notice.getId());
        }
        log.info("公告已发布：noticeId={}, publishTime={}, operator={}, 触达 {} 人",
                id, publishTime, SecurityUtils.getUserId(), noticeTargetResidentIds(id).size());
    }

    /** 公告目标居民：COMMUNITY 目标取社区在住居民（经 residence_relation），
     *  全系统广播取全部 ACTIVE 居民；仅 GUEST 目标的公告（DEF-046，居民不可见）
     *  不向居民触达，其余无 COMMUNITY 目标情形维持全员口径不变 */
    private List<Long> noticeTargetResidentIds(Long noticeId) {
        List<NoticeTarget> targets = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getNoticeId, noticeId)
                        .eq(NoticeTarget::getTargetType, "COMMUNITY"));
        if (targets.isEmpty()) {
            Long guestRows = noticeTargetMapper.selectCount(new LambdaQueryWrapper<NoticeTarget>()
                    .eq(NoticeTarget::getNoticeId, noticeId)
                    .eq(NoticeTarget::getTargetType, "GUEST"));
            if (guestRows != null && guestRows > 0) {
                return List.of();
            }
            return residentMapper.selectList(new LambdaQueryWrapper<Resident>()
                            .eq(Resident::getStatus, "ACTIVE"))
                    .stream().map(Resident::getId).toList();
        }
        List<Long> communityIds = targets.stream().map(NoticeTarget::getTargetId).toList();
        return residenceRelationMapper.selectList(new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                        .in(com.community.residence.resident.entity.ResidenceRelation::getCommunityId, communityIds))
                .stream().map(com.community.residence.resident.entity.ResidenceRelation::getResidentId)
                .distinct().toList();
    }

    /* 撤回公告：PUBLISHED → WITHDRAWN */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "NOTICE", targetId = "#id", content = "'撤回公告：' + #reason")
    public void withdraw(Long id, String reason) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.PUBLISHED.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅已发布的公告可撤回");
        }
        notice.setStatus(NoticeStatus.WITHDRAWN);
        noticeMapper.updateById(notice);
        log.info("公告已撤回：noticeId={}, reason={}, operator={}", id, reason, SecurityUtils.getUserId());
    }

    /* 记录查看：同一用户同一公告仅一次（唯一约束兜底）；浏览计数直接累加（P2 优化 Redis） */
    @Transactional(rollbackFor = Exception.class)
    public void recordView(Long id) {
        Notice notice = requireNotice(id);
        if (!NoticeStatus.PUBLISHED.equals(notice.getStatus())) {
            throw new ResourceNotFoundException("公告不存在或已下线");
        }
        Long userId = SecurityUtils.getUserId();
        Long exists = viewRecordMapper.selectCount(new LambdaQueryWrapper<NoticeViewRecord>()
                .eq(NoticeViewRecord::getNoticeId, id)
                .eq(NoticeViewRecord::getUserId, userId));
        if (exists == 0) {
            NoticeViewRecord record = new NoticeViewRecord();
            record.setNoticeId(id);
            record.setUserId(userId);
            viewRecordMapper.insert(record);
            notice.setViewCount(notice.getViewCount() == null ? 1 : notice.getViewCount() + 1);
            noticeMapper.updateById(notice);
        }
    }

    /** 公告查看记录分页（ADMIN；公告归属已在入口校验） */
    public PageVO<NoticeViewRecordVO> viewers(Long id, long page, long size) {
        requireNotice(id);
        Page<NoticeViewRecord> result = viewRecordMapper.selectPage(new Page<>(page, Math.min(size, 100)),
                new LambdaQueryWrapper<NoticeViewRecord>()
                        .eq(NoticeViewRecord::getNoticeId, id)
                        .orderByDesc(NoticeViewRecord::getViewTime));
        List<Long> userIds = result.getRecords().stream()
                .map(NoticeViewRecord::getUserId).distinct().toList();
        Map<Long, Resident> residents = userIds.isEmpty() ? Map.of()
                : residentMapper.selectBatchIds(userIds).stream()
                        .collect(Collectors.toMap(Resident::getId, Function.identity()));
        List<NoticeViewRecordVO> records = result.getRecords().stream()
                .map(r -> new NoticeViewRecordVO(r.getUserId(),
                        residents.containsKey(r.getUserId())
                                ? residents.get(r.getUserId()).getRealName() : null,
                        r.getViewTime()))
                .toList();
        return PageVO.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    public Notice requireNotice(Long id) {
        Notice notice = noticeMapper.selectById(id);
        if (notice == null) {
            throw new ResourceNotFoundException("公告不存在");
        }
        return notice;
    }

    /* 管理权限：SUPER_ADMIN 全局；ADMIN 限公告目标社区在绑定集合内（无目标的广播仅超管管理）。
       目标社区含 BUILDING 目标经归属社区解析（楼栋定向公告的管理范围与其归属社区一致） */
    private void checkManageAccess(Notice notice) {
        if (SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            return;
        }
        if (resolveNoticeCommunityIds(notice.getId()).stream()
                .noneMatch(SecurityUtils.getCommunityIds()::contains)) {
            throw new ForbiddenException("无权管理该公告");
        }
    }

    /** 公告全部目标归属社区：COMMUNITY 目标直取 + BUILDING 目标经 building.community_id 解析 */
    private List<Long> resolveNoticeCommunityIds(Long noticeId) {
        List<NoticeTarget> targets = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getNoticeId, noticeId));
        List<Long> communityIds = new java.util.ArrayList<>();
        List<Long> buildingIds = new java.util.ArrayList<>();
        for (NoticeTarget target : targets) {
            if ("COMMUNITY".equals(target.getTargetType())) {
                communityIds.add(target.getTargetId());
            } else if ("BUILDING".equals(target.getTargetType())) {
                buildingIds.add(target.getTargetId());
            }
        }
        if (!buildingIds.isEmpty()) {
            buildingMapper.selectBatchIds(buildingIds).stream()
                    .map(com.community.residence.community.entity.Building::getCommunityId)
                    .forEach(communityIds::add);
        }
        return communityIds.stream().distinct().toList();
    }

    /** 居民在住社区集合（经 residence_relation；无居住关系返回空集合） */
    private List<Long> residentCommunityIds(Long residentId) {
        return residenceRelationMapper.selectList(
                        new LambdaQueryWrapper<com.community.residence.resident.entity.ResidenceRelation>()
                                .eq(com.community.residence.resident.entity.ResidenceRelation::getResidentId, residentId))
                .stream().map(com.community.residence.resident.entity.ResidenceRelation::getCommunityId)
                .distinct().toList();
    }

    /** 公告对居民社区集合可见：无目标=广播可见；任一目标（含楼栋归属）落在集合内可见 */
    private boolean visibleToResidentCommunities(Notice notice, List<Long> communityIds) {
        List<Long> targetCommunities = resolveNoticeCommunityIds(notice.getId());
        return targetCommunities.isEmpty()
                || targetCommunities.stream().anyMatch(communityIds::contains);
    }

    /** 公告对游客可见（DEF-046）：无 COMMUNITY/BUILDING 定向行（纯广播）或存在 GUEST 目标行；
     *  与列表 GUEST_VISIBLE_FILTER_SQL 同口径（详情与列表一致，防直连详情绕过） */
    private boolean visibleToGuest(Long noticeId) {
        List<NoticeTarget> targets = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getNoticeId, noticeId));
        boolean hasScopedTarget = targets.stream().anyMatch(t ->
                "COMMUNITY".equals(t.getTargetType()) || "BUILDING".equals(t.getTargetType()));
        boolean hasGuestTarget = targets.stream().anyMatch(t ->
                "GUEST".equals(t.getTargetType()));
        return !hasScopedTarget || hasGuestTarget;
    }

    /** 社区集合可见的定向公告 ID（COMMUNITY 目标 + BUILDING 目标归属社区在集合内） */
    private List<Long> targetedNoticeIdsForCommunities(List<Long> communityIds) {
        if (communityIds.isEmpty()) {
            return List.of();
        }
        List<NoticeTarget> targets = noticeTargetMapper.selectList(null);
        List<Long> buildingIds = targets.stream()
                .filter(t -> "BUILDING".equals(t.getTargetType()))
                .map(NoticeTarget::getTargetId).distinct().toList();
        Map<Long, Long> buildingCommunity = buildingIds.isEmpty() ? Map.of()
                : buildingMapper.selectBatchIds(buildingIds).stream()
                        .collect(Collectors.toMap(
                                com.community.residence.community.entity.Building::getId,
                                com.community.residence.community.entity.Building::getCommunityId));
        return targets.stream()
                .filter(t -> "COMMUNITY".equals(t.getTargetType())
                        && communityIds.contains(t.getTargetId())
                        || "BUILDING".equals(t.getTargetType())
                        && communityIds.contains(buildingCommunity.get(t.getTargetId())))
                .map(NoticeTarget::getNoticeId)
                .distinct().toList();
    }

    /** 按社区取公告 ID 集合；为空时经 wrapper 注入恒假条件（in 空集合会生成非法 SQL） */
    private List<Long> resolveNoticeIdsByCommunity(Long communityId, LambdaQueryWrapper<Notice> wrapper) {
        List<Long> noticeIds = noticeTargetMapper.selectList(
                        new LambdaQueryWrapper<NoticeTarget>()
                                .eq(NoticeTarget::getTargetType, "COMMUNITY")
                                .eq(NoticeTarget::getTargetId, communityId))
                .stream().map(NoticeTarget::getNoticeId).toList();
        if (noticeIds.isEmpty()) {
            noticeIds = List.of(-1L);
        }
        return noticeIds;
    }

    private boolean isExpired(Notice notice) {
        return notice.getEndTime() != null && notice.getEndTime().isBefore(LocalDateTime.now());
    }

    private NoticeVO toVO(Notice notice) {
        NoticeVO vo = NoticeVO.from(notice);
        List<NoticeTarget> targets = noticeTargetMapper.selectList(
                new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getNoticeId, notice.getId()));
        List<NoticeVO.TargetItem> targetItems = new java.util.ArrayList<>();
        for (NoticeTarget target : targets) {
            String name = null;
            if ("COMMUNITY".equals(target.getTargetType())) {
                Community community = communityMapperQuiet(target.getTargetId());
                name = community != null ? community.getName() : null;
                if (vo.getCommunityId() == null) {
                    vo.setCommunityId(target.getTargetId());
                    vo.setCommunityName(name);
                }
            } else if ("BUILDING".equals(target.getTargetType())) {
                com.community.residence.community.entity.Building building =
                        buildingMapper.selectById(target.getTargetId());
                name = building != null ? building.getName() : null;
            }
            /* GUEST 目标行落库 target_id=0，读回按契约置空（写入侧即不携带 targetId） */
            targetItems.add(NoticeVO.TargetItem.of(
                    target.getTargetType(),
                    "GUEST".equals(target.getTargetType()) ? null : target.getTargetId(),
                    name));
        }
        vo.setTargets(targetItems);
        SysUser publisher = sysUserMapper.selectById(notice.getPublisherId());
        if (publisher != null) {
            vo.setPublisherName(publisher.getRealName());
        }
        return vo;
    }

    /** 公告关联社区可能已被删除（社区删除受保护，理论不发生，防御式处理） */
    private Community communityMapperQuiet(Long communityId) {
        try {
            return communityService.requireCommunity(communityId);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }
}
