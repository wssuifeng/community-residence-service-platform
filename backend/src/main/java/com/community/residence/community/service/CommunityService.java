package com.community.residence.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.community.dto.CreateCommunityDTO;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.vo.CommunityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 社区业务逻辑：全局运营主体的增删改查与停用/启用。
 * 社区表是数据级权限锚点（ADMIN 按 id IN 绑定社区过滤，由拦截器处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityMapper communityMapper;

    /** 创建社区（仅超管，功能级权限在 Controller 声明） */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVO create(CreateCommunityDTO dto) {
        Community community = new Community();
        applyDto(community, dto);
        community.setStatus(CommonStatus.ACTIVE);
        communityMapper.insert(community);
        return CommunityVO.from(community);
    }

    /** 更新社区基本信息（超管全局；社区管理员限绑定社区） */
    @Transactional(rollbackFor = Exception.class)
    public CommunityVO update(Long id, CreateCommunityDTO dto) {
        Community community = requireCommunity(id);
        SecurityUtils.checkCommunityAccess(community.getId());
        applyDto(community, dto);
        communityMapper.updateById(community);
        return CommunityVO.from(community);
    }

    public CommunityVO getById(Long id) {
        return CommunityVO.from(requireCommunity(id));
    }

    /** 社区分页列表（公开；ADMIN 由拦截器自动过滤绑定社区） */
    public PageVO<CommunityVO> page(long page, long size, String status, String keyword) {
        LambdaQueryWrapper<Community> wrapper = new LambdaQueryWrapper<Community>()
                .eq(StringUtils.hasText(status), Community::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Community::getName, keyword)
                        .or()
                        .like(Community::getAddress, keyword))
                .orderByDesc(Community::getId);
        Page<Community> result = communityMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(CommunityVO::from));
    }

    /** 停用/启用社区（仅超管；停用后该社区下所有写业务被 COMMUNITY_INACTIVE 拦截） */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        Community community = requireCommunity(id);
        community.setStatus(status);
        communityMapper.updateById(community);
        log.info("社区状态变更：communityId={}, status={}, operator={}",
                id, status, SecurityUtils.getUser() != null ? SecurityUtils.getUserId() : "system");
    }

    /** 按ID取社区，不存在抛 404；供本模块与其他模块校验引用 */
    public Community requireCommunity(Long id) {
        Community community = communityMapper.selectById(id);
        if (community == null) {
            throw new ResourceNotFoundException("社区不存在");
        }
        return community;
    }

    /** 引用校验：社区必须存在且运营中（楼栋/资源等子资源创建时调用） */
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
        community.setContactPhone(dto.getContactPhone());
        community.setContactPerson(dto.getContactPerson());
        community.setDescription(dto.getDescription());
    }
}
