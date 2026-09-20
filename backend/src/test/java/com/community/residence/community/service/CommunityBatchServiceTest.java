package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.dto.BatchCommunityIdsDTO;
import com.community.residence.community.dto.BatchCommunityStatusDTO;
import com.community.residence.community.vo.BatchOperationResultVO;
import com.community.residence.community.vo.CommunityVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 社区批量管理测试：逐社区独立事务的部分成功语义（一个失败不影响其余）、
 * 失败项原因与名称回填、状态白名单校验、批次上限与空列表保护。
 * CommunityService 为 mock（其级联删除本身由 CommunityCascadeDeleteTest 覆盖）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityBatchService 批量删除/批量状态单元测试")
class CommunityBatchServiceTest {

    @Mock
    private CommunityService communityService;

    @InjectMocks
    private CommunityBatchService communityBatchService;

    /* ---- 批量删除 ---- */

    @Test
    @DisplayName("批量删除部分成功：不存在的社区失败，其余社区仍删除（逐社区独立事务）")
    void batchDelete_partialSuccess() {
        /* lenient：同一方法在批内以其他 ID 被调用，避免 strict-stubs 的参数不匹配判定 */
        lenient().doThrow(new ResourceNotFoundException("社区不存在"))
                .when(communityService).delete(2L);
        when(communityService.getById(2L)).thenThrow(new ResourceNotFoundException("社区不存在"));

        BatchOperationResultVO vo = communityBatchService.batchDelete(idsDto(1L, 2L, 3L));

        assertThat(vo.getSuccessIds()).containsExactly(1L, 3L);
        assertThat(vo.getFailures()).hasSize(1);
        assertThat(vo.getFailures().get(0).getId()).isEqualTo(2L);
        assertThat(vo.getFailures().get(0).getName()).isNull();
        assertThat(vo.getFailures().get(0).getReason()).isEqualTo("社区不存在");
        verify(communityService, times(3)).delete(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("批量删除失败项回填社区名称：业务失败但社区仍存在时带上名称")
    void batchDelete_failureCarriesCommunityName() {
        lenient().doThrow(new BusinessException(ErrorCode.OPERATION_FAILED, "级联删除失败"))
                .when(communityService).delete(2L);
        CommunityVO community = new CommunityVO();
        community.setId(2L);
        community.setName("退场社区");
        when(communityService.getById(2L)).thenReturn(community);

        BatchOperationResultVO vo = communityBatchService.batchDelete(idsDto(1L, 2L));

        assertThat(vo.getSuccessIds()).containsExactly(1L);
        assertThat(vo.getFailures()).hasSize(1);
        assertThat(vo.getFailures().get(0).getId()).isEqualTo(2L);
        assertThat(vo.getFailures().get(0).getName()).isEqualTo("退场社区");
        assertThat(vo.getFailures().get(0).getReason()).isEqualTo("级联删除失败");
    }

    @Test
    @DisplayName("批量删除：重复 ID 去重后只删一次")
    void batchDelete_dedupesIds() {
        BatchOperationResultVO vo = communityBatchService.batchDelete(idsDto(1L, 1L, 2L));

        assertThat(vo.getSuccessIds()).containsExactly(1L, 2L);
        verify(communityService, times(2)).delete(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("批量删除：空列表 / 仅 null 元素报 INVALID_PARAM，超 50 个报 INVALID_PARAM")
    void batchDelete_emptyAndOverLimitRejected() {
        assertThatThrownBy(() -> communityBatchService.batchDelete(new BatchCommunityIdsDTO()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM));
        assertThatThrownBy(() -> communityBatchService.batchDelete(idsDto((Long) null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM));

        BatchCommunityIdsDTO tooMany = new BatchCommunityIdsDTO();
        tooMany.setIds(LongStream.rangeClosed(1, 51).boxed().toList());
        assertThatThrownBy(() -> communityBatchService.batchDelete(tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50")
                .hasMessageContaining("51");
        verify(communityService, never()).delete(org.mockito.ArgumentMatchers.anyLong());
    }

    /* ---- 批量状态变更 ---- */

    @Test
    @DisplayName("批量状态变更：非法状态值（ARCHIVED/空）报 INVALID_PARAM，不调用任何更新")
    void batchStatus_invalidStatusRejected() {
        assertThatThrownBy(() -> communityBatchService.batchUpdateStatus(statusDto("ARCHIVED", 1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM))
                .hasMessageContaining("ACTIVE");
        assertThatThrownBy(() -> communityBatchService.batchUpdateStatus(statusDto(null, 1L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM));
        verify(communityService, never()).updateStatus(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    @DisplayName("批量状态变更部分成功：不存在的社区记失败，其余照常停用")
    void batchStatus_partialSuccess() {
        lenient().doThrow(new ResourceNotFoundException("社区不存在"))
                .when(communityService).updateStatus(2L, "INACTIVE");
        when(communityService.getById(2L)).thenThrow(new ResourceNotFoundException("社区不存在"));

        BatchOperationResultVO vo = communityBatchService.batchUpdateStatus(
                statusDto("INACTIVE", 1L, 2L, 3L));

        assertThat(vo.getSuccessIds()).containsExactly(1L, 3L);
        assertThat(vo.getFailures()).hasSize(1);
        assertThat(vo.getFailures().get(0).getReason()).isEqualTo("社区不存在");
        verify(communityService).updateStatus(1L, "INACTIVE");
        verify(communityService).updateStatus(3L, "INACTIVE");
    }

    /* ---- 脚手架 ---- */

    private BatchCommunityIdsDTO idsDto(Long... ids) {
        BatchCommunityIdsDTO dto = new BatchCommunityIdsDTO();
        dto.setIds(new ArrayList<>(java.util.Arrays.asList(ids)));
        return dto;
    }

    private BatchCommunityStatusDTO statusDto(String status, Long... ids) {
        BatchCommunityStatusDTO dto = new BatchCommunityStatusDTO();
        dto.setIds(new ArrayList<>(java.util.Arrays.asList(ids)));
        dto.setStatus(status);
        return dto;
    }
}
