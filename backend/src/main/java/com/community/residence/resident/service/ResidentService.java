package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.resident.dto.RegisterResidentDTO;
import com.community.residence.resident.dto.UpdateProfileDTO;
import com.community.residence.resident.dto.UpdateResidentStatusDTO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.ResidentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 居民账号业务逻辑：注册（开关控制）、个人资料、改密、冻结。
 * resident 表无 community_id 列（拦截器跳过），ADMIN 的居民范围过滤
 * 经 residence_relation 在业务层处理（居民列表场景默认查全量，前端按社区筛选）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResidentService {

    private final ResidentMapper residentMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final SysConfigService sysConfigService;

    /** 居民自助注册：受 registration.enabled 配置控制；用户名/手机号唯一 */
    @Transactional(rollbackFor = Exception.class)
    public ResidentVO register(RegisterResidentDTO dto) {
        if (!sysConfigService.isRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.REGISTRATION_DISABLED);
        }
        checkUsernameUnique(dto.getUsername());
        checkPhoneUnique(dto.getPhone(), null);

        Resident resident = new Resident();
        resident.setUsername(dto.getUsername());
        resident.setPasswordHash(passwordEncoder.encode(dto.getPassword()));
        resident.setRealName(dto.getRealName());
        resident.setPhone(dto.getPhone());
        resident.setEmail(dto.getEmail());
        resident.setIdCard(dto.getIdCard());
        resident.setStatus(CommonStatus.ACTIVE);
        residentMapper.insert(resident);
        log.info("居民注册成功：residentId={}, username={}", resident.getId(), resident.getUsername());
        return ResidentVO.from(resident);
    }

    /** 个人资料（居民本人） */
    public ResidentVO profile() {
        return ResidentVO.from(requireResident(SecurityUtils.getUserId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ResidentVO updateProfile(UpdateProfileDTO dto) {
        Resident resident = requireResident(SecurityUtils.getUserId());
        checkPhoneUnique(dto.getPhone(), resident.getId());
        resident.setRealName(dto.getRealName());
        resident.setPhone(dto.getPhone());
        resident.setEmail(dto.getEmail());
        residentMapper.updateById(resident);
        return ResidentVO.from(resident);
    }

    /* 修改密码：校验旧密码；改完吊销令牌强制重新登录 */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String oldPassword, String newPassword) {
        Resident resident = requireResident(SecurityUtils.getUserId());
        if (!passwordEncoder.matches(oldPassword, resident.getPasswordHash())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        resident.setPasswordHash(passwordEncoder.encode(newPassword));
        residentMapper.updateById(resident);
        tokenRevocationService.revokeResident(resident.getId());
    }

    public ResidentVO getById(Long id) {
        return ResidentVO.from(requireResident(id));
    }

    public PageVO<ResidentVO> page(long page, long size, String status, String keyword) {
        LambdaQueryWrapper<Resident> wrapper = new LambdaQueryWrapper<Resident>()
                .eq(StringUtils.hasText(status), Resident::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Resident::getRealName, keyword)
                        .or().like(Resident::getPhone, keyword)
                        .or().like(Resident::getUsername, keyword))
                .orderByDesc(Resident::getId);
        Page<Resident> result = residentMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(ResidentVO::from));
    }

    /* 冻结/解冻：冻结即时吊销全部令牌 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, UpdateResidentStatusDTO dto) {
        Resident resident = requireResident(id);
        resident.setStatus(dto.getStatus());
        residentMapper.updateById(resident);
        if (CommonStatus.FROZEN.equals(dto.getStatus())) {
            tokenRevocationService.revokeResident(id);
        }
        log.info("居民账号状态变更：residentId={}, status={}, reason={}, operator={}",
                id, dto.getStatus(), dto.getReason(), SecurityUtils.getUserId());
    }

    public Resident requireResident(Long id) {
        Resident resident = residentMapper.selectById(id);
        if (resident == null) {
            throw new ResourceNotFoundException("居民不存在");
        }
        return resident;
    }

    private void checkUsernameUnique(String username) {
        Long count = residentMapper.selectCount(new LambdaQueryWrapper<Resident>()
                .eq(Resident::getUsername, username));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "用户名已存在");
        }
    }

    private void checkPhoneUnique(String phone, Long excludeId) {
        Long count = residentMapper.selectCount(new LambdaQueryWrapper<Resident>()
                .eq(Resident::getPhone, phone)
                .ne(excludeId != null, Resident::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "手机号已存在");
        }
    }
}
