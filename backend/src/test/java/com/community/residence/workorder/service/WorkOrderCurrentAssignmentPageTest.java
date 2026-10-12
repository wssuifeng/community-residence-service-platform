package com.community.residence.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.workorder.entity.WorkOrder;
import com.community.residence.workorder.mapper.WorkOrderAssignmentMapper;
import com.community.residence.workorder.mapper.WorkOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DEF-177：当前派单归属在分页前收敛，历史派单不授予列表读取权限。 */
@ExtendWith(MockitoExtension.class)
class WorkOrderCurrentAssignmentPageTest {
    @Mock private WorkOrderMapper workOrderMapper;
    @Mock private WorkOrderAssignmentMapper assignmentMapper;
    @InjectMocks private WorkOrderService service;

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), WorkOrder.class);
    }

    @Test
    void administratorFilter_usesLatestAssignmentBeforePagination() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            assertCurrentScope(42L, List.of(42L));
        }
    }

    @Test
    void staffScope_usesLatestAssignmentBeforePagination() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF)).thenReturn(true);
            security.when(SecurityUtils::getUserId).thenReturn(7L);
            assertCurrentScope(null, List.of(7L));
        }
    }

    @Test
    void staffCannotWidenScope_byFilteringAnotherAssignee() {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(() -> SecurityUtils.hasRole(RoleConstants.STAFF)).thenReturn(true);
            security.when(SecurityUtils::getUserId).thenReturn(7L);
            assertCurrentScope(42L, List.of(42L, 7L));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertCurrentScope(Long filter, List<Long> expectedParameters) {
        when(workOrderMapper.selectPage(any(), any()))
                .thenReturn(new Page<WorkOrder>(2, 20, 0).setRecords(List.of()));
        var result = service.page(2, 20, null, null, null, null, null,
                null, null, filter, null);
        ArgumentCaptor<LambdaQueryWrapper<WorkOrder>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(workOrderMapper).selectPage(any(), captor.capture());
        var wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("EXISTS", "current_assignment.work_order_id = work_order.id",
                "current_assignment.assignee_id =", "MAX(latest_assignment.id)",
                "latest_assignment.work_order_id = work_order.id");
        assertThat(sql.split("EXISTS", -1).length - 1).isEqualTo(expectedParameters.size());
        assertThat(wrapper.getParamNameValuePairs().values()).containsExactlyInAnyOrderElementsOf(expectedParameters);
        verifyNoInteractions(assignmentMapper);
        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPage()).isEqualTo(2);
    }
}
