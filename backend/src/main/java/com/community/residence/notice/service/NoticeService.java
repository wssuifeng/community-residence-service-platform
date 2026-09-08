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
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
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

    private final NoticeMapper noticeMapper;
    private final NoticeTargetMapper noticeTargetMapper;
    private final NoticeViewRecordMapper viewRecordMapper;
    private final CommunityService communityService;
    private final SysUserMapper sysUserMapper;
    private final ResidentMapper residentMapper;

    /* 创建公告：初始 DRAFT；社区定向写 notice_target；无 communityId 为全系统广播 */
    @Transactional(rollbackFor = Exception.class)
    public NoticeVO create(CreateNoticeDTO dto) {
        if (dto.getCommunityId() != null) {
            communityService.requireActiveCommunity(dto.getCommunityId());
            SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        } else if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "社区管理员必须指定目标社区");
        }

        Notice notice = new Notice();
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setPublisherId(SecurityUtils.getUserId());
        notice.setStatus(NoticeStatus.DRAFT);
        notice.setPublishTime(dto.getPublishTime());
        notice.setEndTime(dto.getEndTime() != null
                ? dto.getEndTime()
                : dto.getPublishTime().plusDays(DEFAULT_VALID_DAYS));
        notice.setViewCount(0);
        noticeMapper.insert(notice);

        if (dto.getCommunityId() != null) {
            NoticeTarget target = new NoticeTarget();
            target.setNoticeId(notice.getId());
            target.setTargetType("COMMUNITY");
            target.setTargetId(dto.getCommunityId());
            noticeTargetMapper.insert(target);
        }
        return toVO(notice, dto.getCommunityId());
    }

    /* 更新公告：仅 DRAFT 可改；目标社区不可变更（重新建公告） */
    @Transactional(rollbackFor = Exception.class)
    public NoticeVO update(Long id, CreateNoticeDTO dto) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.DRAFT.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅草稿状态的公告可修改");
        }
        if (dto.getCommunityId() != null) {
            SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        }
        notice.setTitle(dto.getTitle());
        notice.setContent(dto.getContent());
        notice.setPublishTime(dto.getPublishTime());
        notice.setEndTime(dto.getEndTime() != null
                ? dto.getEndTime()
                : dto.getPublishTime().plusDays(DEFAULT_VALID_DAYS));
        noticeMapper.updateById(notice);
        return toVO(notice, resolveCommunityId(notice.getId()));
    }

    /* 删除公告：仅 DRAFT/WITHDRAWN 可删（已发布公告走撤回流程） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Notice notice = requireNotice(id);
        checkManageAccess(notice);
        if (!NoticeStatus.DRAFT.equals(notice.getStatus())
                && !NoticeStatus.WITHDRAWN.equals(notice.getStatus())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "仅草稿或已撤回的公告可删除");
        }
        noticeMapper.deleteById(id);
        noticeTargetMapper.delete(new LambdaQueryWrapper<NoticeTarget>()
                .eq(NoticeTarget::getNoticeId, id));
        log.info("公告已删除：noticeId={}, operator={}", id, SecurityUtils.getUserId());
    }

    /** 公告详情：普通访问仅 PUBLISHED 且未过期；管理端角色放行（归属已在入口校验） */
    public NoticeVO getById(Long id) {
        Notice notice = requireNotice(id);
        boolean managerView = SecurityUtils.hasRole(RoleConstants.ADMIN)
                || SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN);
        if (!managerView) {
            if (!NoticeStatus.PUBLISHED.equals(notice.getStatus()) || isExpired(notice)) {
                throw new ResourceNotFoundException("公告不存在或已下线");
            }
        }
        return toVO(notice, resolveCommunityId(id));
    }

    /**
     * 公告分页列表：匿名/普通用户仅 PUBLISHED 且未过期；
     * 管理端角色返回全部状态（ADMIN 限绑定社区，业务层经 notice_target 过滤）。
     */
    public PageVO<NoticeVO> page(long page, long size, Long communityId, String keyword) {
        boolean managerView = SecurityUtils.hasRole(RoleConstants.ADMIN)
                || SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN);

        LambdaQueryWrapper<Notice> wrapper = new LambdaQueryWrapper<Notice>()
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Notice::getTitle, keyword)
                        .or().like(Notice::getContent, keyword))
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
        }

        Page<Notice> result = noticeMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(n -> toVO(n, resolveCommunityId(n.getId()))));
    }

    /* 发布公告：DRAFT → PUBLISHED；publishTime 早于当前时间立即生效 */
    @Transactional(rollbackFor = Exception.class)
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
        log.info("公告已发布：noticeId={}, publishTime={}, operator={}",
                id, publishTime, SecurityUtils.getUserId());
    }

    /* 撤回公告：PUBLISHED → WITHDRAWN */
    @Transactional(rollbackFor = Exception.class)
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

    /* 管理权限：SUPER_ADMIN 全局；ADMIN 限公告目标社区在绑定集合内（无目标的广播仅超管管理） */
    private void checkManageAccess(Notice notice) {
        if (SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) {
            return;
        }
        Long communityId = resolveCommunityId(notice.getId());
        if (communityId == null || !SecurityUtils.getCommunityIds().contains(communityId)) {
            throw new ForbiddenException("无权管理该公告");
        }
    }

    /** 取公告的社区目标 ID（全系统广播返回 null） */
    private Long resolveCommunityId(Long noticeId) {
        return noticeTargetMapper.selectList(new LambdaQueryWrapper<NoticeTarget>()
                        .eq(NoticeTarget::getNoticeId, noticeId)
                        .eq(NoticeTarget::getTargetType, "COMMUNITY"))
                .stream().findFirst().map(NoticeTarget::getTargetId).orElse(null);
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

    private NoticeVO toVO(Notice notice, Long communityId) {
        NoticeVO vo = NoticeVO.from(notice);
        if (communityId != null) {
            vo.setCommunityId(communityId);
            Community community = communityMapperQuiet(communityId);
            if (community != null) {
                vo.setCommunityName(community.getName());
            }
        }
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
