package com.community.residence.auth.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 查询留痕：身份撞号、删除账号、缺失原始参数与组合筛选；不替代数据范围拦截器集成测试。 */
@ExtendWith(MockitoExtension.class)
class OperationLogCoverageTest {
    @Mock SysOperationLogMapper operationLogMapper;
    @Mock SysUserMapper sysUserMapper;
    @Mock ResidentMapper residentMapper;
    @InjectMocks OperationLogService service;

    @BeforeAll static void tableMetadata() {
        for (Class<?> entity : List.of(SysOperationLog.class, Resident.class, SysUser.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "bm-log"), entity);
        }
    }

    private SysOperationLog row(Long id, String type) {
        SysOperationLog log = new SysOperationLog(); log.setId(10L); log.setOperatorId(id);
        log.setOperatorType(type); log.setTargetType("HOUSE"); log.setOperationType("UPDATE");
        return log;
    }

    private void pageRows(List<SysOperationLog> rows) {
        when(operationLogMapper.selectPage(any(), any())).thenAnswer(call -> {
            Page<SysOperationLog> page = call.getArgument(0);
            page.setRecords(rows); page.setTotal(rows.size()); return page;
        });
    }

    @Test void collidingResidentAndAdminIdsRemainSeparateAndNamesAreBatched() {
        pageRows(List.of(row(9L, "RESIDENT"), row(9L, "ADMIN"), row(9L, "STAFF"), row(9L, "RESIDENT")));
        Resident resident = new Resident(); resident.setId(9L); resident.setRealName("居民甲");
        SysUser user = new SysUser(); user.setId(9L); user.setRealName("管理甲");
        when(residentMapper.selectList(any())).thenReturn(List.of(resident));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        var result = service.page(1, 20, null, null, null, null, null, null, null, null);
        assertThat(result.getRecords()).extracting(v -> v.getOperatorName()).containsExactly("居民甲", "管理甲", "管理甲", "居民甲");
        verify(residentMapper, times(1)).selectList(any());
        verify(sysUserMapper, times(1)).selectList(any());
    }

    @Test void missingAccountsUnknownTypeAndSystemKeepHonestFallbacks() {
        pageRows(List.of(row(8L, "RESIDENT"), row(9L, "SUPER_ADMIN"), row(10L, "LEGACY"), row(null, "SYSTEM")));
        when(residentMapper.selectList(any())).thenReturn(List.of());
        when(sysUserMapper.selectList(any())).thenReturn(List.of());
        var result = service.page(1, 20, null, null, null, null, null, null, null, null);
        assertThat(result.getRecords()).extracting(v -> v.getOperatorName()).containsExactly("RESIDENT#8", "SUPER_ADMIN#9", "LEGACY#10", "系统");
    }

    @Test void blankRealNamesFallBackToUsernameInEachSpace() {
        pageRows(List.of(row(9L, "RESIDENT"), row(9L, "ADMIN")));
        Resident resident = new Resident(); resident.setId(9L); resident.setRealName(" "); resident.setUsername("resident-alias");
        SysUser user = new SysUser(); user.setId(9L); user.setUsername("admin-alias");
        when(residentMapper.selectList(any())).thenReturn(List.of(resident));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user));
        assertThat(service.page(1, 20, null, null, null, null, null, null, null, null).getRecords())
                .extracting(v -> v.getOperatorName()).containsExactly("resident-alias", "admin-alias");
    }

    @Test void emptyPageDoesNotQueryIdentityTablesOrInventRows() {
        pageRows(List.of());
        assertThat(service.page(1, 20, null, " ", "", null, null, null, "", " ").getRecords()).isEmpty();
        verifyNoInteractions(residentMapper, sysUserMapper);
    }

    @Test void combinationFiltersAreBoundParametersAndPageSizeIsCapped() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 12, 0, 0), end = start.plusDays(1);
        when(operationLogMapper.selectPage(any(), any())).thenAnswer(call -> {
            Page<SysOperationLog> page = call.getArgument(0);
            assertThat(page.getSize()).isEqualTo(100);
            LambdaQueryWrapper<SysOperationLog> query = call.getArgument(1);
            String sql = query.getSqlSegment();
            assertThat(sql).contains("operator_id", "operator_type", "operation_type", "target_type", "content", "created_at", "ORDER BY id DESC");
            assertThat(query.getParamNameValuePairs().values()).contains(9L, "ADMIN", "UPDATE", "HOUSE", start, end, "%quote'%");
            assertThat(sql).doesNotContain("quote'");
            page.setRecords(List.of()); return page;
        });
        service.page(2, 999, 9L, "ADMIN", "UPDATE", "HOUSE", start, end, "COMMUNITY", "quote'");
    }

    @Test void missingLogIsNotReportedAsEmptySuccess() {
        assertThatThrownBy(() -> service.getById(999L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(residentMapper, sysUserMapper);
    }

    @Test void legacyContentDoesNotManufactureOriginalRequestParameters() {
        SysOperationLog log = row(null, "SYSTEM"); log.setContent("历史纯文本");
        when(operationLogMapper.selectById(10L)).thenReturn(log);
        var result = service.getById(10L);
        assertThat(result.getDescription()).isEqualTo("历史纯文本");
        assertThat(result.getRequestParams()).isNull();
        assertThat(result.getModule()).isEqualTo("COMMUNITY");
        assertThat(result.getOperatorName()).isEqualTo("系统");
        verifyNoInteractions(residentMapper, sysUserMapper);
    }
}
