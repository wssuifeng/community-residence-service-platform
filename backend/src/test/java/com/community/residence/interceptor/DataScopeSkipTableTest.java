package com.community.residence.interceptor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/**
 * 数据级权限白名单回归（DEF-070 同型）：**无 `community_id` 列的表必须登记在
 * `DataScopeInterceptor.SKIP_TABLES`**，否则社区管理员（ADMIN）查询会被注入
 * `community_id IN (...)` → `BadSqlGrammarException: Unknown column 'community_id'` → HTTP 500。
 * <p>
 * 本用例同时守住两个方向：①无该列的表不得被注入（V22 押金退还记录 / C10 拉黑审计）；
 * ②有该列的表仍必须注入（防止白名单被过度扩张而丢掉数据级权限）。
 */
@DisplayName("数据级权限跳过表白名单回归（DEF-070 同型）")
class DataScopeSkipTableTest {

    private final DataScopeInterceptor interceptor = new DataScopeInterceptor();

    /** 模拟 ADMIN 查询：执行拦截器改写后返回 SQL 文本 */
    private String rewriteAsCommunityAdmin(String tableName) throws Exception {
        Configuration configuration = new MybatisConfiguration();
        String sql = "SELECT id FROM " + tableName + " WHERE id = ?";
        SqlSource sqlSource = new org.apache.ibatis.builder.StaticSqlSource(configuration, sql);
        MappedStatement ms = new MappedStatement.Builder(configuration, "test.select", sqlSource,
                SqlCommandType.SELECT).build();
        BoundSql boundSql = new BoundSql(configuration, sql, List.of(), null);

        UserContext admin = new UserContext();
        admin.setUserId(9L);
        admin.setRole(RoleConstants.ADMIN);
        admin.setCommunityIds(Set.of(1L, 2L));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(admin);
            interceptor.beforeQuery(null, ms, null, null, null, boundSql);
        }
        return boundSql.getSql();
    }

    @Test
    @DisplayName("V22 押金退还记录（无 community_id 列）：不注入过滤条件，社区管理员查询不再 500")
    void leaseDepositRefund_skipped() throws Exception {
        String sql = rewriteAsCommunityAdmin("lease_deposit_refund");

        assertThat(sql).doesNotContain("community_id");
        assertThat(sql).isEqualTo("SELECT id FROM lease_deposit_refund WHERE id = ?");
    }

    @Test
    @DisplayName("C10 拉黑审计日志（无 community_id 列）：同型缺陷一并收口，不注入")
    void sysBlacklistLog_skipped() throws Exception {
        String sql = rewriteAsCommunityAdmin("sys_blacklist_log");

        assertThat(sql).doesNotContain("community_id");
    }

    @Test
    @DisplayName("有 community_id 列的表仍按绑定社区注入（白名单未被过度扩张）")
    void leaseRecord_injected() throws Exception {
        String sql = rewriteAsCommunityAdmin("lease_record");

        /* 绑定社区集合为 Set.of(1,2)（JDK ImmutableCollections 迭代顺序按 JVM SALT 随机化），
           故只断言"注入了 1 与 2 两个社区"而不锁定书写顺序，避免 JVM 间 flake */
        assertThat(sql).containsPattern("community_id IN \\((?:1, 2|2, 1)\\)");
    }

    @Test
    @DisplayName("community 本表按 id 注入（既有特例未被白名单破坏）")
    void communityTable_filteredById() throws Exception {
        String sql = rewriteAsCommunityAdmin("community");

        assertThat(sql).containsPattern("id IN \\((?:1, 2|2, 1)\\)");
    }
    @Test
    @DisplayName("父级已鉴权的新增子表没有community_id，ADMIN查询不得盲注")
    void scopedChildTablesUseParentAuthorization() throws Exception {
        for (String table : List.of("house_image", "lease_contract_term")) {
            assertThat(rewriteAsCommunityAdmin(table)).as(table).doesNotContain("community_id");
        }
    }

    @Test
    @DisplayName("图片消息表具有community_id，仍保留数据库社区过滤")
    void chatImageWithCommunityColumnStillFiltered() throws Exception {
        assertThat(rewriteAsCommunityAdmin("chat_message_image")).contains("chat_message_image.community_id IN");
    }

}
