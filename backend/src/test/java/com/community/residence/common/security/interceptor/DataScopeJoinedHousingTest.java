package com.community.residence.common.security.interceptor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/** JOIN加锁与社区查询必须限定主表，不放宽原有数据权限。 */
class DataScopeJoinedHousingTest {
    private String rewrite(String sql, Set<Long> scope) throws Exception {
        var configuration = new MybatisConfiguration();
        var ms = new MappedStatement.Builder(configuration, "test.housingLock", new StaticSqlSource(configuration, sql), SqlCommandType.SELECT).build();
        var bound = new BoundSql(configuration, sql, List.of(), null);
        try (MockedStatic<SecurityUtils> identity = mockStatic(SecurityUtils.class)) {
            identity.when(SecurityUtils::getUser).thenReturn(new UserContext(2L, "admin", "ADMIN", scope));
            new DataScopeInterceptor().beforeQuery(null, ms, null, null, null, bound);
        }
        return bound.getSql();
    }
    @Test void actualHouseHousingLockQualifiesTheMainHouse() throws Exception {
        assertThat(rewrite("SELECT hh.id FROM house hh JOIN housing ON housing.house_id=hh.id WHERE housing.id=? AND hh.is_deleted=0 FOR UPDATE", Set.of(2L)))
            .contains("hh.community_id IN (2)", "FOR UPDATE");
    }
    @Test void noAliasJoinIsAlsoQualified() throws Exception {
        assertThat(rewrite("SELECT house.id FROM house JOIN housing ON housing.house_id=house.id", Set.of(2L)))
            .contains("house.community_id IN (2)");
    }
    @Test void communityRootUsesQualifiedId() throws Exception {
        assertThat(rewrite("SELECT c.id FROM community c JOIN building b ON b.community_id=c.id", Set.of(2L)))
            .contains("c.id IN (2)").doesNotContain("c.community_id");
    }
    @Test void noBoundCommunityStaysDeniedEvenWithJoin() throws Exception {
        assertThat(rewrite("SELECT hh.id FROM house hh JOIN housing ON housing.house_id=hh.id", Set.of()))
            .contains("hh.community_id IN ('__NO_COMMUNITY__')");
    }
}
