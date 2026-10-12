package com.community.residence.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.vo.OperationLogVO;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.result.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;

/** 操作日志业务逻辑：查询侧（写入由 AOP 切面/P2 阶段覆盖）；ADMIN 由拦截器按 community_id 过滤 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final SysOperationLogMapper operationLogMapper;
    private final SysUserMapper sysUserMapper;
    private final ResidentMapper residentMapper;

    public PageVO<OperationLogVO> page(long page, long size, Long operatorId, String operatorType,
                                       String operationType, String targetType,
                                       LocalDateTime startTime, LocalDateTime endTime, String module, String keyword) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<SysOperationLog>()
                .eq(operatorId != null, SysOperationLog::getOperatorId, operatorId)
                .eq(StringUtils.hasText(operatorType), SysOperationLog::getOperatorType, operatorType)
                .eq(StringUtils.hasText(operationType), SysOperationLog::getOperationType, operationType)
                .eq(StringUtils.hasText(targetType), SysOperationLog::getTargetType, targetType)
                .in(StringUtils.hasText(module), SysOperationLog::getTargetType,
                        StringUtils.hasText(module) ? OperationLogVO.targetsForModule(module) : List.of())
                .like(StringUtils.hasText(keyword), SysOperationLog::getContent, keyword)
                .ge(startTime != null, SysOperationLog::getCreatedAt, startTime)
                .le(endTime != null, SysOperationLog::getCreatedAt, endTime)
                .orderByDesc(SysOperationLog::getId);
        Page<SysOperationLog> result = operationLogMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        var converted = result.convert(OperationLogVO::from);
        fillOperatorNames(converted.getRecords());
        return PageVO.of(converted);
    }

    public OperationLogVO getById(Long id) {
        SysOperationLog log = operationLogMapper.selectById(id);
        if (log == null) {
            throw new ResourceNotFoundException("日志不存在");
        }
        OperationLogVO vo = OperationLogVO.from(log);
        fillOperatorNames(List.of(vo));
        return vo;
    }

    /* 分页结果按身份空间批量回填姓名；撞号居民与管理员不可串名，不逐行N+1。 */
    private void fillOperatorNames(List<OperationLogVO> rows) {
        List<Long> residents = rows.stream().filter(row -> "RESIDENT".equals(row.getOperatorType()))
                .map(OperationLogVO::getOperatorId).filter(java.util.Objects::nonNull).distinct().toList();
        List<Long> users = rows.stream().filter(row -> java.util.Arrays.asList("ADMIN", "SUPER_ADMIN", "STAFF").contains(row.getOperatorType()))
                .map(OperationLogVO::getOperatorId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> residentNames = residents.isEmpty() ? Map.of() : residentMapper.selectList(
                new LambdaQueryWrapper<Resident>().select(Resident::getId, Resident::getRealName, Resident::getUsername)
                        .in(Resident::getId, residents)).stream().collect(Collectors.toMap(Resident::getId,
                        r -> StringUtils.hasText(r.getRealName()) ? r.getRealName() : r.getUsername()));
        Map<Long, String> userNames = users.isEmpty() ? Map.of() : sysUserMapper.selectList(
                new LambdaQueryWrapper<SysUser>().select(SysUser::getId, SysUser::getRealName, SysUser::getUsername)
                        .in(SysUser::getId, users)).stream().collect(Collectors.toMap(SysUser::getId,
                        u -> StringUtils.hasText(u.getRealName()) ? u.getRealName() : u.getUsername()));
        rows.forEach(row -> {
            if (row.getOperatorId() == null) return;
            String name = ("RESIDENT".equals(row.getOperatorType()) ? residentNames : userNames).get(row.getOperatorId());
            if (name != null) row.setOperatorName(name);
        });
    }
}
