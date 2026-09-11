package com.community.residence.resident.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import com.community.residence.resident.dto.AdminCreateResidentDTO;
import com.community.residence.resident.dto.RegisterResidentDTO;
import com.community.residence.resident.dto.UpdateProfileDTO;
import com.community.residence.resident.dto.UpdateResidentStatusDTO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.vo.AdminCreateResidentVO;
import com.community.residence.resident.vo.ResidentImportVO;
import com.community.residence.resident.vo.ResidentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 居民账号业务逻辑：注册（开关控制）、个人资料、改密、冻结。
 * resident 表无 community_id 列（拦截器跳过），ADMIN 的居民范围过滤
 * 经 residence_relation 在业务层处理（接口设计 9.2.1.7/9.2.1.8：
 * ADMIN 限绑定社区内居民；DEF-010 修复：列表注入社区过滤，详情越范围 404）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResidentService {

    private final ResidentMapper residentMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final SysConfigService sysConfigService;
    private final SysOperationLogMapper sysOperationLogMapper;

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

    /** 居民详情：ADMIN 限绑定社区内居民（经 residence_relation 关联，越范围 404；
        SUPER_ADMIN/STAFF 放行——STAFF 数据权限走派单关系，由工单模块约束） */
    public ResidentVO getById(Long id) {
        Resident resident = requireResident(id);
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)
                && !boundResidentIds(SecurityUtils.getCommunityIds()).contains(id)) {
            throw new ResourceNotFoundException("居民不存在");
        }
        return ResidentVO.from(resident);
    }

    /** 居民列表：ADMIN 自动注入绑定社区过滤（经 residence_relation，DEF-010） */
    public PageVO<ResidentVO> page(long page, long size, String status, String keyword) {
        LambdaQueryWrapper<Resident> wrapper = new LambdaQueryWrapper<Resident>()
                .eq(StringUtils.hasText(status), Resident::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(Resident::getRealName, keyword)
                        .or().like(Resident::getPhone, keyword)
                        .or().like(Resident::getUsername, keyword))
                .orderByDesc(Resident::getId);
        if (SecurityUtils.hasRole(RoleConstants.ADMIN)) {
            List<Long> boundIds = boundResidentIds(SecurityUtils.getCommunityIds());
            if (boundIds.isEmpty()) {
                return PageVO.of(List.of(), 0, page, size);
            }
            wrapper.in(Resident::getId, boundIds);
        }
        Page<Resident> result = residentMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(ResidentVO::from));
    }

    /** 绑定社区内在住/曾住居民 ID 集合（居民与社区的关联唯一来源是 residence_relation） */
    private List<Long> boundResidentIds(java.util.Set<Long> communityIds) {
        if (communityIds == null || communityIds.isEmpty()) {
            return List.of();
        }
        return residenceRelationMapper.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                        .in(ResidenceRelation::getCommunityId, communityIds))
                .stream().map(ResidenceRelation::getResidentId).distinct().toList();
    }

    /* 冻结/解冻：冻结即时吊销全部令牌 */
    @Transactional(rollbackFor = Exception.class)
    @com.community.residence.log.annotation.OperationLog(operationType = "STATUS", targetType = "RESIDENT", targetId = "#id", content = "'居民账号状态变更为 ' + #dto.status + '：' + #dto.reason")
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

    /**
     * 管理员代建居民（R8 v1.2）：初始密码服务端生成（手机号后 6 位），
     * 明文仅本次返回；resident 无 community_id 列，社区经数据级权限锚点
     * （绑定社区校验）确认管理范围；操作写 sys_operation_log。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminCreateResidentVO adminCreate(AdminCreateResidentDTO dto) {
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        String username = StringUtils.hasText(dto.getUsername())
                ? dto.getUsername() : "r" + dto.getPhone();
        String initialPassword = dto.getPhone().substring(5);
        AdminCreateResidentVO vo = createResidentAccount(username, dto.getRealName(),
                dto.getPhone(), dto.getIdCard(), initialPassword, null);
        logOperation("CREATE", vo.getResidentId(), dto.getCommunityId(),
                "{\"realName\":\"" + dto.getRealName() + "\",\"source\":\"ADMIN_CREATE\"}");
        log.info("管理员代建居民：residentId={}, username={}, operator={}",
                vo.getResidentId(), username, SecurityUtils.getUserId());
        return vo;
    }

    /**
     * CSV 批量导入（R8 v1.2 扩展）：UTF-8，首行表头（社区ID,姓名,手机号,证件号）；
     * 部分成功语义——逐行独立事务（经 self 代理避免同类内调用 @Transactional 失效，
     * 此处改为行级 try-catch + 手动回退：插入失败即该行失败，不影响已成功行）；
     * CSV 解析自实现（处理逗号/引号转义），不引入 EasyExcel（AGENTS 排除先例）。
     */
    public ResidentImportVO importCsv(InputStream in) {
        List<String[]> rows = parseCsv(in);
        ResidentImportVO result = new ResidentImportVO();
        List<ResidentImportVO.SuccessRow> successRows = new ArrayList<>();
        List<ResidentImportVO.FailRow> failRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            String[] cols = rows.get(i);
            try {
                if (cols.length < 3) {
                    throw new IllegalArgumentException("列数不足（需至少 社区ID,姓名,手机号）");
                }
                Long communityId = Long.parseLong(cols[0].trim());
                String realName = cols[1].trim();
                String phone = cols[2].trim();
                String idCard = cols.length > 3 ? cols[3].trim() : null;
                if (!phone.matches("^1[3-9]\\d{9}$")) {
                    throw new IllegalArgumentException("手机号格式不正确");
                }
                SecurityUtils.checkCommunityAccess(communityId);
                AdminCreateResidentVO created = createResidentAccount(
                        "r" + phone, realName, phone, idCard, phone.substring(5), communityId);
                successRows.add(ResidentImportVO.SuccessRow.of(
                        rowNo, created.getUsername(), created.getInitialPassword()));
                logOperation("CREATE", created.getResidentId(), communityId,
                        "{\"realName\":\"" + realName + "\",\"source\":\"CSV_IMPORT\"}");
            } catch (Exception e) {
                String reason = e instanceof BusinessException be
                        ? be.getMessage() : e.getMessage();
                failRows.add(ResidentImportVO.FailRow.of(rowNo, reason));
            }
        }
        result.setTotal(rows.size());
        result.setSuccess(successRows.size());
        result.setFail(failRows.size());
        result.setSuccessRows(successRows);
        result.setFailRows(failRows);
        log.info("居民批量导入：total={}, success={}, fail={}, operator={}",
                result.getTotal(), result.getSuccess(), result.getFail(), SecurityUtils.getUserId());
        return result;
    }

    /* 账号创建公共路径：唯一性校验 + 落库，返回代建结果 */
    private AdminCreateResidentVO createResidentAccount(String username, String realName,
                                                        String phone, String idCard,
                                                        String initialPassword, Long communityId) {
        checkUsernameUnique(username);
        checkPhoneUnique(phone, null);
        Resident resident = new Resident();
        resident.setUsername(username);
        resident.setPasswordHash(passwordEncoder.encode(initialPassword));
        resident.setRealName(realName);
        resident.setPhone(phone);
        resident.setIdCard(idCard);
        resident.setStatus(CommonStatus.ACTIVE);
        residentMapper.insert(resident);
        return AdminCreateResidentVO.of(resident.getId(), username, initialPassword);
    }

    private void logOperation(String operationType, Long targetId, Long communityId, String content) {
        SysOperationLog entry = new SysOperationLog();
        entry.setOperatorId(SecurityUtils.getUserId());
        entry.setOperatorType("ADMIN");
        entry.setCommunityId(communityId);
        entry.setOperationType(operationType);
        entry.setTargetType("RESIDENT");
        entry.setTargetId(targetId);
        entry.setContent(content);
        entry.setCreatedAt(LocalDateTime.now());
        sysOperationLogMapper.insert(entry);
    }

    /* 轻量 CSV 解析：支持双引号包裹与引号内转义（"" 表示字面引号，逗号不分割） */
    private List<String[]> parseCsv(InputStream in) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            boolean first = true;
            while (line != null) {
                if (first) {
                    /* 首行为表头，跳过 */
                    first = false;
                } else if (!line.isBlank()) {
                    rows.add(splitCsvLine(line));
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "CSV 文件读取失败");
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "CSV 无数据行");
        }
        return rows;
    }

    private String[] splitCsvLine(String line) {
        List<String> cols = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cols.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cols.add(current.toString());
        return cols.toArray(new String[0]);
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
