package com.community.residence.community.service;

import com.community.residence.common.constant.CommonStatus;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.BatchCommunityIdsDTO;
import com.community.residence.community.dto.BatchCommunityStatusDTO;
import com.community.residence.community.vo.BatchOperationResultVO;
import com.community.residence.community.vo.BatchOperationResultVO.Failure;
import com.community.residence.community.vo.CommunityVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 社区批量管理（仅超管，功能级权限在 Controller 声明）：批量级联删除 + 批量停用/启用。
 *
 * 逐社区独立事务：本类持有的是 {@link CommunityService} 的 Spring 代理引用，
 * 循环调用其 delete/updateStatus 均经代理进入，每个社区各自成为一个事务、
 * 各自写一条操作留痕（@OperationLog 切面按方法调用生效）——
 * 一个社区失败不影响其余社区（部分成功语义），且不存在自调用导致的事务失效
 * （批量方法不直接调本类自己的 @Transactional 方法）。
 * 单次上限 50 个社区、重复 ID 去重（LinkedHashSet 保持请求顺序）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityBatchService {

    /** 单次批量处理上限（与前端多选规模一致，防误操作打爆库） */
    static final int MAX_BATCH_SIZE = 50;

    private final CommunityService communityService;

    /** 批量级联删除：逐社区独立事务，失败项记录原因后继续 */
    public BatchOperationResultVO batchDelete(BatchCommunityIdsDTO dto) {
        List<Long> ids = normalizeIds(dto.getIds());
        List<Long> successIds = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            try {
                communityService.delete(id);
                successIds.add(id);
            } catch (Exception e) {
                failures.add(failure(id, e));
            }
        }
        log.info("社区批量删除：total={}, success={}, fail={}, operator={}",
                ids.size(), successIds.size(), failures.size(),
                SecurityUtils.getUser() != null ? SecurityUtils.getUserId() : "system");
        return BatchOperationResultVO.of(successIds, failures);
    }

    /** 批量停用/启用：状态取值白名单在服务层再校验（DTO @Pattern 只覆盖请求体形态） */
    public BatchOperationResultVO batchUpdateStatus(BatchCommunityStatusDTO dto) {
        List<Long> ids = normalizeIds(dto.getIds());
        String status = dto.getStatus() == null ? null : dto.getStatus().trim().toUpperCase();
        if (!CommonStatus.ACTIVE.equals(status) && !CommonStatus.INACTIVE.equals(status)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "社区状态仅支持 ACTIVE/INACTIVE");
        }
        List<Long> successIds = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        for (Long id : ids) {
            try {
                communityService.updateStatus(id, status);
                successIds.add(id);
            } catch (Exception e) {
                failures.add(failure(id, e));
            }
        }
        log.info("社区批量状态变更：status={}, total={}, success={}, fail={}, operator={}",
                status, ids.size(), successIds.size(), failures.size(),
                SecurityUtils.getUser() != null ? SecurityUtils.getUserId() : "system");
        return BatchOperationResultVO.of(successIds, failures);
    }

    /** ID 列表归一：非空、去空值、去重（保持请求顺序）、上限校验 */
    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请至少选择一个社区");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(ids);
        unique.remove(null);
        if (unique.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请至少选择一个社区");
        }
        if (unique.size() > MAX_BATCH_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "单次最多处理 " + MAX_BATCH_SIZE + " 个社区，当前 " + unique.size() + " 个");
        }
        return new ArrayList<>(unique);
    }

    /** 失败项：回查社区名称（社区本体仍在时补充信息，不在则留空） */
    private Failure failure(Long id, Exception e) {
        String name = null;
        try {
            CommunityVO community = communityService.getById(id);
            name = community != null ? community.getName() : null;
        } catch (Exception lookup) {
            /* 回查仅用于失败项可读性（社区不存在/已删时无名称），失败不影响结果结构 */
            log.debug("批量操作失败项回查社区名称未命中：communityId={}", id, lookup);
        }
        return Failure.of(id, name, e.getMessage());
    }
}
