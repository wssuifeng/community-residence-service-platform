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

/** 操作日志业务逻辑：查询侧（写入由 AOP 切面/P2 阶段覆盖）；ADMIN 由拦截器按 community_id 过滤 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final SysOperationLogMapper operationLogMapper;

    public PageVO<OperationLogVO> page(long page, long size, Long operatorId, String operatorType,
                                       String operationType, String targetType,
                                       LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<SysOperationLog> wrapper = new LambdaQueryWrapper<SysOperationLog>()
                .eq(operatorId != null, SysOperationLog::getOperatorId, operatorId)
                .eq(StringUtils.hasText(operatorType), SysOperationLog::getOperatorType, operatorType)
                .eq(StringUtils.hasText(operationType), SysOperationLog::getOperationType, operationType)
                .eq(StringUtils.hasText(targetType), SysOperationLog::getTargetType, targetType)
                .ge(startTime != null, SysOperationLog::getCreatedAt, startTime)
                .le(endTime != null, SysOperationLog::getCreatedAt, endTime)
                .orderByDesc(SysOperationLog::getId);
        Page<SysOperationLog> result = operationLogMapper.selectPage(new Page<>(page, Math.min(size, 100)), wrapper);
        return PageVO.of(result.convert(OperationLogVO::from));
    }

    public OperationLogVO getById(Long id) {
        SysOperationLog log = operationLogMapper.selectById(id);
        if (log == null) {
            throw new ResourceNotFoundException("日志不存在");
        }
        return OperationLogVO.from(log);
    }
}
