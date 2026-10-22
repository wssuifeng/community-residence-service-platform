package com.community.residence.common.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.community.dto.CreateCommunityDTO;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.vo.CommunityVO;
import com.community.residence.common.workorder.entity.ServiceCategory;
import com.community.residence.common.workorder.mapper.ServiceCategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 小区业务逻辑：全局运营主体的增删改查与停用/启用。
 * 小区表是数据级权限锚点（ADMIN 按 id IN 绑定小区过滤，由拦截器处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private final StructureBusinessCodeService structureBusinessCodeService;

    /** 自动通过路径的默认租期（月）：请求未指定时回落到此值 */
    private static final int DEFAULT_LEASE_MONTHS = 12;

    private final CommunityMapper communityMapper;
    private final CommunityResponsibleService communityResponsibleService;
    private final ServiceCategoryMapper serviceCategoryMapper;
    /* V18/V19 新增表的级联清理依赖（外键指向 community / lease_record / service_category） */
    /* V20 新增表的级联清理依赖（外键同时指向 lease_record / community / house，R70 退租申请） */
    /* V22 新增表的级联清理依赖（外键指向 lease_record，R72 押金退还记录；
       无 community_id，按 leaseIds 批删且须早于租约物理删除） */

    /** 创建小区（仅超管，功能级权限在 Controller 声明） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "CREATE", targetType = "COMMUNITY", targetId = "#result.id", content = "'创建小区：' + #dto.name")
    public CommunityVO create(CreateCommunityDTO dto) {
        Community community = new Community();
        applyDto(community, dto);
        community.setStatus(CommonStatus.ACTIVE);
        community.setBusinessCode(structureBusinessCodeService.community(dto.getBusinessCode()));
        communityMapper.insert(community);
        int categoryOrder = 0;
        for (var item : com.community.residence.common.workorder.service.DefaultServiceCategories.ITEMS) {
            ServiceCategory category = new ServiceCategory(); category.setCommunityId(community.getId());
            category.setName(item.name()); category.setDescription(item.description()); category.setSortOrder(++categoryOrder); category.setIsActive(1);
            serviceCategoryMapper.insert(category);
        }
        communityResponsibleService.synchronizeBinding(community.getId(), community.getResponsibleAdminId());
        communityResponsibleService.fillContacts(java.util.List.of(community));
        return CommunityVO.from(community);
    }

    /** 更新小区基本信息（超管全局；小区管理员限绑定小区） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "UPDATE", targetType = "COMMUNITY", targetId = "#id", content = "'更新小区：' + #dto.name")
    public CommunityVO update(Long id, CreateCommunityDTO dto) {
        structureBusinessCodeService.lockExistingCommunity(id);
        Community community = requireCommunity(id);
        SecurityUtils.checkCommunityAccess(community.getId());
        applyDto(community, dto);
        if (dto.getBusinessCode() != null && !dto.getBusinessCode().isBlank() && !dto.getBusinessCode().trim().equals(community.getBusinessCode())) community.setBusinessCode(structureBusinessCodeService.updateCommunity(dto.getBusinessCode(), id));
        communityMapper.updateById(community);
        communityResponsibleService.synchronizeBinding(community.getId(), community.getResponsibleAdminId());
        communityResponsibleService.fillContacts(java.util.List.of(community));
        return CommunityVO.from(community);
    }

    /** 授权来源修改与责任人保存共用小区锁，避免互相覆盖手工授权来源。 */
    public Community lockForAuthorization(Long id) {
        structureBusinessCodeService.lockExistingCommunity(id);
        return requireCommunity(id);
    }

    public CommunityVO getById(Long id) {
        return CommunityVO.from(requireCommunity(id));
    }

    /** 小区分页列表（公开；ADMIN 由拦截器自动过滤绑定小区） */
    public PageVO<CommunityVO> page(long page, long size, String status, String keyword) {
        LambdaQueryWrapper<Community> wrapper = new LambdaQueryWrapper<Community>()
                .eq(StringUtils.hasText(status), Community::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Community::getName, keyword)
                        .or()
                        .like(Community::getAddress, keyword))
                .orderByDesc(Community::getId);
        Page<Community> result = communityMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        communityResponsibleService.fillContacts(result.getRecords());
        return PageVO.of(result.convert(CommunityVO::from));
    }

    /** 停用/启用小区（仅超管；停用后该小区下所有写业务被 COMMUNITY_INACTIVE 拦截） */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "COMMUNITY", targetId = "#id", content = "'小区状态变更为 ' + #status")
    public void updateStatus(Long id, String status) {
        Community community = requireCommunity(id);
        community.setStatus(status);
        communityMapper.updateById(community);
        log.info("小区状态变更：communityId={}, status={}, operator={}",
                id, status, SecurityUtils.getUser() != null ? SecurityUtils.getUserId() : "system");
    }

    /** 按ID取小区，不存在抛 404；供本模块与其他模块校验引用 */
    public Community requireCommunity(Long id) {
        Community community = communityMapper.selectById(id);
        if (community == null) {
            throw new ResourceNotFoundException("小区不存在");
        }
        communityResponsibleService.fillContacts(java.util.List.of(community));
        return community;
    }

    /** 引用校验：小区必须存在且运营中（楼栋/资源等子资源创建时调用） */
    public Community requireActiveCommunity(Long id) {
        Community community = requireCommunity(id);
        if (!CommonStatus.ACTIVE.equals(community.getStatus())) {
            throw new BusinessException(ErrorCode.COMMUNITY_INACTIVE);
        }
        return community;
    }

    private void applyDto(Community community, CreateCommunityDTO dto) {
        community.setName(dto.getName());
        community.setAddress(dto.getAddress());
        communityResponsibleService.validate(dto.getResponsibleAdminId());
        community.setResponsibleAdminId(dto.getResponsibleAdminId());
        community.setDescription(dto.getDescription());
        /* 自动化开关：字段缺省按「关闭 + 12 个月」落库，避免前端旧版本漏传时写成 NULL */
        community.setAutoApproveResidence(dto.getAutoApproveResidence() == null ? 0 : dto.getAutoApproveResidence());
        community.setDefaultLeaseMonths(
                dto.getDefaultLeaseMonths() == null || dto.getDefaultLeaseMonths() < 1
                        ? DEFAULT_LEASE_MONTHS
                        : dto.getDefaultLeaseMonths());
    }
}
