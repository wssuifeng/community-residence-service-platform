package com.community.residence.common.resident.service;

import com.community.residence.common.auth.service.SysConfigService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.auth.entity.SysOperationLog;
import com.community.residence.common.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.auth.service.TokenRevocationService;
import com.community.residence.common.web.constant.CommonStatus;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.result.PageVO;
import com.community.residence.common.resident.dto.AdminCreateResidentDTO;
import com.community.residence.common.resident.dto.RegisterResidentDTO;
import com.community.residence.common.resident.dto.UpdateProfileDTO;
import com.community.residence.common.resident.dto.UpdateResidentStatusDTO;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.vo.AdminCreateResidentVO;
import com.community.residence.common.resident.vo.ResidentImportVO;
import com.community.residence.common.resident.vo.ResidentVO;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Validator;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.resident.dto.AdminCreateRelationDTO;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
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
    private final com.community.residence.common.community.service.StructureBusinessCodeService structureBusinessCodeService;

    private final ResidentMapper residentMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final SysConfigService sysConfigService;
    private final SysOperationLogMapper sysOperationLogMapper;
    private final HouseMapper houseMapper;
    private final CommunityService communityService;
    private final ResidenceRelationService residenceRelationService;
    private final PlatformTransactionManager transactionManager;
    private final Validator validator;

    /** 居民自助注册：受 registration.enabled 配置控制；用户名/手机号唯一 */
    @Transactional(rollbackFor = Exception.class)
    public ResidentVO register(RegisterResidentDTO dto) {
        if (!sysConfigService.isRegistrationEnabled()) {
            throw new BusinessException(ErrorCode.REGISTRATION_DISABLED);
        }
        checkUsernameUnique(dto.getUsername().trim());
        checkPhoneUnique(dto.getPhone(), null);

        Resident resident = new Resident();
        resident.setUsername(dto.getUsername().trim());
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

    /* 同住邀请可精确匹配未绑定账号，但不开放跨社区居民台账或敏感资料。 */
    public java.util.List<com.community.residence.common.resident.vo.CoResidentCandidateVO> coResidentCandidates(Long houseId, String keyword) {
        var house=houseMapper.selectById(houseId);
        if(house==null) throw new com.community.residence.common.web.exception.ResourceNotFoundException("房屋不存在");
        SecurityUtils.checkCommunityAccess(house.getCommunityId());
        String term=keyword==null?"":keyword.trim();
        if(term.isEmpty()) return java.util.List.of();
        if(!"OCCUPIED".equals(house.getStatus())) throw new BusinessException(ErrorCode.INVALID_PARAM,"请先选择有效主居住关系的房屋");
        return residentMapper.selectList(new LambdaQueryWrapper<Resident>().eq(Resident::getStatus,"ACTIVE")
                .and(w->w.eq(Resident::getUsername,term).or().eq(Resident::getPhone,term))).stream().map(r->{
                    var item=new com.community.residence.common.resident.vo.CoResidentCandidateVO();
                    item.setId(r.getId());item.setUsername(r.getUsername());item.setRealName(r.getRealName());return item;
                }).toList();
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
    @com.community.residence.common.log.annotation.OperationLog(operationType = "STATUS", targetType = "RESIDENT", targetId = "#id", content = "'居民账号状态变更为 ' + #dto.status + '：' + #dto.reason")
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

    /* 代建明确区分仅账号和账号+关系；整个请求事务内保存，不自动建立或签署租约。 */
    @Transactional(rollbackFor = Exception.class)
    public AdminCreateResidentVO adminCreate(AdminCreateResidentDTO dto) {
        return provisionResident(dto, "ADMIN_CREATE");
    }

    /* 导入每行独立REQUIRES_NEW事务，成功结果必须在提交后收集；失败行不会留下账号或关系。 */
    public ResidentImportVO importCsv(InputStream in) {
        return importCsv(in, null);
    }

    public ResidentImportVO importCsv(InputStream in, Long defaultCommunityId) {
        List<String[]> rows = parseCsv(in);
        ResidentImportVO result = new ResidentImportVO();
        List<ResidentImportVO.SuccessRow> successRows = new ArrayList<>();
        List<ResidentImportVO.FailRow> failRows = new ArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            int rowNo = i + 1;
            try {
                String[] columns = rows.get(i);
                if (columns.length == 0) throw new IllegalArgumentException("业务编号模板必须保留九列，请检查本行逗号数量");
                if (columns[0].startsWith("@") && java.util.Arrays.stream(columns).skip(1).allMatch(v -> v == null || v.isBlank() || "@CODE".equals(v))) { skipped++; continue; }
                AdminCreateResidentVO created = transaction.execute(status -> provisionResident(parseResidentRow(columns, defaultCommunityId), "CSV_IMPORT"));
                ResidentImportVO.SuccessRow row = ResidentImportVO.SuccessRow.of(rowNo,
                        created.getUsername(), created.getInitialPassword());
                row.setResidentId(created.getResidentId());
                row.setRelationId(created.getRelationId());
                row.setHouseId(created.getHouseId());
                row.setBusinessCode(created.getBusinessCode());
                successRows.add(row);
            } catch (Exception exception) {
                String reason = exception instanceof BusinessException ? exception.getMessage()
                        : exception instanceof IllegalArgumentException ? exception.getMessage() : "本行保存失败，请核对数据或联系管理员";
                failRows.add(ResidentImportVO.FailRow.of(rowNo, reason));
            }
        }
        result.setTotal(rows.size());
        result.setSkipped(skipped);
        result.setSuccess(successRows.size());
        result.setFail(failRows.size());
        result.setSuccessRows(successRows);
        result.setFailRows(failRows);
        log.info("居民批量导入：total={}, success={}, fail={}, operator={}",
                result.getTotal(), result.getSuccess(), result.getFail(), SecurityUtils.getUserId());
        return result;
    }

    private AdminCreateResidentDTO parseResidentRow(String[] columns, Long defaultCommunityId) {
        if (columns.length < 3 || columns.length > 10) {
            throw new IllegalArgumentException("列数不符（需社区ID,姓名,手机号及可选关系字段，最多10列）");
        }
        AdminCreateResidentDTO dto = new AdminCreateResidentDTO();
        com.community.residence.common.community.entity.House codedHouse = null;
        if (columns[0].startsWith("@")) {
            String code = columns[0].substring(1).trim();
            if (!code.isEmpty()) codedHouse = structureBusinessCodeService.resolve(code);
            Long communityId = codedHouse == null ? defaultCommunityId : codedHouse.getCommunityId();
            if (communityId == null) throw new IllegalArgumentException("仅建账号时请在上传窗口选择社区");
            if (defaultCommunityId != null && !defaultCommunityId.equals(communityId)) throw new IllegalArgumentException("房屋业务编号不属于上传窗口所选社区");
            dto.setCommunityId(communityId);
        } else dto.setCommunityId(Long.parseLong(columns[0].trim()));
        dto.setRealName(columns[1].trim());
        dto.setPhone(columns[2].trim());
        dto.setIdCard(optionalColumn(columns, 3));
        dto.setUsername(optionalColumn(columns, 4));
        dto.setEmail(optionalColumn(columns, 5));
        String houseId = optionalColumn(columns, 6);
        dto.setHouseId("@CODE".equals(houseId) ? (codedHouse == null ? null : codedHouse.getId()) : houseId == null ? null : Long.parseLong(houseId));
        dto.setRelationType(optionalColumn(columns, 7));
        String moveIn = optionalColumn(columns, 8);
        try {
            dto.setMoveInDate(moveIn == null ? null : LocalDate.parse(moveIn));
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("入住日期须为YYYY-MM-DD");
        }
        dto.setRemark(optionalColumn(columns, 9));
        return dto;
    }

    private String optionalColumn(String[] columns, int index) {
        return columns.length > index && StringUtils.hasText(columns[index]) ? columns[index].trim() : null;
    }

    /* 单建与每行CSV共用DTO校验、权限/房屋归属及持久化；审计不记录密码和证件原文。 */
    private AdminCreateResidentVO provisionResident(AdminCreateResidentDTO dto, String source) {
        var violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            String message = violations.stream().map(jakarta.validation.ConstraintViolation::getMessage)
                    .sorted().collect(java.util.stream.Collectors.joining("；"));
            throw new BusinessException(ErrorCode.INVALID_PARAM, message);
        }
        SecurityUtils.checkCommunityAccess(dto.getCommunityId());
        communityService.requireCommunity(dto.getCommunityId());
        if (dto.getHouseId() == null) {
            if (dto.getRelationType() != null || dto.getMoveInDate() != null || StringUtils.hasText(dto.getRemark())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "仅建账号时不能填写部分居住关系；请同时填写房屋、关系类型和入住日期");
            }
        } else {
            if (!StringUtils.hasText(dto.getRelationType()) || dto.getMoveInDate() == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "登记居住关系须同时填写房屋、关系类型和入住日期");
            }
            var house = houseMapper.selectByIdForUpdate(dto.getHouseId());
            if (house == null) {
                throw new ResourceNotFoundException("房屋不存在或无权访问");
            }
            SecurityUtils.checkCommunityAccess(house.getCommunityId());
            if (!house.getCommunityId().equals(dto.getCommunityId())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "所选房屋不属于所选社区");
            }
        }
        String username = StringUtils.hasText(dto.getUsername()) ? dto.getUsername().trim() : "r" + dto.getPhone();
        checkUsernameUnique(username);
        checkPhoneUnique(dto.getPhone(), null);
        String initialPassword = dto.getPhone().substring(5);
        Resident resident = new Resident();
        resident.setUsername(username);
        resident.setPasswordHash(passwordEncoder.encode(initialPassword));
        resident.setRealName(dto.getRealName().trim());
        resident.setPhone(dto.getPhone());
        resident.setIdCard(dto.getIdCard());
        resident.setEmail(dto.getEmail());
        resident.setStatus(CommonStatus.ACTIVE);
        try {
            residentMapper.insert(resident);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "用户名或手机号已存在");
        }
        AdminCreateResidentVO result = AdminCreateResidentVO.of(resident.getId(), username, initialPassword);
        if (dto.getHouseId() != null) {
            AdminCreateRelationDTO relation = new AdminCreateRelationDTO();
            relation.setResidentId(resident.getId());
            relation.setAnchorRelationId(dto.getAnchorRelationId());
            relation.setHouseId(dto.getHouseId());
            relation.setRelationType(dto.getRelationType());
            relation.setMoveInDate(dto.getMoveInDate());
            relation.setRemark(dto.getRemark());
            var saved = residenceRelationService.adminCreate(relation);
            result.setRelationId(saved.getId());
            result.setHouseId(dto.getHouseId());
            result.setBusinessCode(structureBusinessCodeService.codeForHouse(dto.getHouseId()));
        }
        logOperation("CREATE", resident.getId(), dto.getCommunityId(),
                "{\"source\":\"" + source + "\",\"relationId\":" + result.getRelationId() + ",\"houseId\":" + result.getHouseId() + "}");
        return result;
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
        if (in == null) throw new BusinessException(ErrorCode.INVALID_PARAM, "CSV文件不能为空");
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new java.io.StringReader(decodeCsv(in)))) {
            String line = reader.readLine();
            boolean first = true;
            boolean businessHeader = false;
            while (line != null) {
                if (first) {
                    String header = java.util.Arrays.stream(splitCsvLine(line.replace("\uFEFF", "")))
                            .map(String::trim).collect(java.util.stream.Collectors.joining(","));
                    businessHeader = header.equals("房屋编号,姓名,手机号,证件号,用户名,邮箱,关系类型,入住日期,备注");
                    if (!businessHeader && !header.equals("社区ID,姓名,手机号,证件号")
                            && !header.equals("社区ID,姓名,手机号,证件号,用户名,邮箱,房屋ID,关系类型,入住日期,备注")) {
                        throw new BusinessException(ErrorCode.INVALID_PARAM, "CSV表头不符，请下载当前导入模板");
                    }
                    first = false;
                } else if (!line.isBlank()) {
                    String[] values = splitCsvLine(line);
                    if (businessHeader) {
                        values = values.length != 9 ? new String[0] : new String[]{"@"+values[0],values[1],values[2],values[3],values[4],values[5],"@CODE",values[6],values[7],values[8]};
                    }
                    rows.add(values);
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

    /* CSV下载后经Excel另存常变GBK/UTF-16；严格解码失败才回退，不静默替换字段。 */
    private String decodeCsv(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(5 * 1024 * 1024 + 1);
        if (bytes.length > 5 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "CSV文件不能超过5MB");
        }
        java.nio.charset.Charset charset = StandardCharsets.UTF_8;
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            charset = StandardCharsets.UTF_16;
        }
        try {
            return strictDecode(bytes, charset);
        } catch (java.nio.charset.CharacterCodingException exception) {
            if (!charset.equals(StandardCharsets.UTF_8)) throw exception;
            return strictDecode(bytes, java.nio.charset.Charset.forName("GB18030"));
        }
    }

    private String strictDecode(byte[] bytes, java.nio.charset.Charset charset)
            throws java.nio.charset.CharacterCodingException {
        return charset.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
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
                .and(w -> w.eq(Resident::getUsername, username).or().eq(Resident::getPhone, username)));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "用户名已存在或与已注册手机号冲突");
        }
    }

    private void checkPhoneUnique(String phone, Long excludeId) {
        Long count = residentMapper.selectCount(new LambdaQueryWrapper<Resident>()
                .and(w -> w.eq(Resident::getPhone, phone).or().eq(Resident::getUsername, phone))
                .ne(excludeId != null, Resident::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(ErrorCode.DATA_EXISTS, "手机号已存在或与用户名冲突");
        }
    }
}
