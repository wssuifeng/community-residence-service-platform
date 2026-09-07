package com.community.residence.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.Set;

/**
 * 数据级权限拦截器（架构设计.md §3.2 第二层，JSqlParser 4.6 API）：
 * ADMIN → 强制注入 community_id IN (绑定社区)；SUPER_ADMIN → 不注入；
 * RESIDENT/STAFF 的个人维度过滤涉及具体表语义（resident_id/assignee_id），
 * 由对应模块查询显式约束，不在本拦截器盲注（避免误伤无该列的表）。
 * 未登录（公开接口）不注入。SQL 解析失败视为权限不可判定，拒绝查询。
 */
@Slf4j
public class DataScopeInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        UserContext user = SecurityUtils.getUser();
        if (user == null || !user.isCommunityAdmin()) {
            return;
        }
        Set<Long> communityIds = user.getCommunityIds();
        rewriteSql(ms, boundSql, communityIds != null && !communityIds.isEmpty());
    }

    /** 改写 SQL：community 本表按 id 过滤，其余表按 community_id 过滤；无绑定时注入恒假条件 */
    private void rewriteSql(MappedStatement ms, BoundSql boundSql, boolean hasBound) throws SQLException {
        String originalSql = boundSql.getSql();
        try {
            Select select = (Select) CCJSqlParserUtil.parse(originalSql);
            PlainSelect plain = select.getPlainSelect();

            String filterColumn = isCommunityTable(plain) ? "id" : "community_id";
            InExpression in = new InExpression();
            in.setLeftExpression(new Column(filterColumn));
            ParenthesedExpressionList<net.sf.jsqlparser.expression.Expression> items = new ParenthesedExpressionList<>();
            if (hasBound) {
                for (Long id : SecurityUtils.getUser().getCommunityIds()) {
                    items.add(new LongValue(id));
                }
            } else {
                // ADMIN 未绑定任何社区：恒假条件，杜绝越权看到全量数据
                items.add(new StringValue("__NO_COMMUNITY__"));
            }
            in.setRightExpression(items);

            plain.setWhere(plain.getWhere() == null ? in : new AndExpression(plain.getWhere(), in));

            MetaObject metaObject = SystemMetaObject.forObject(boundSql);
            metaObject.setValue("sql", select.toString());
            log.debug("数据级权限已注入：statement={}, user={}", ms.getId(),
                    SecurityUtils.getUser().getUserId() + ":" + SecurityUtils.getUser().getRole());
        } catch (JSQLParserException | ClassCastException e) {
            /* 解析失败放行原始 SQL 会让 ADMIN 看到全量数据，必须拒绝 */
            log.error("数据级权限 SQL 解析失败，拒绝查询：statement={}", ms.getId(), e);
            throw new SQLException("数据级权限校验失败", e);
        }
    }

    /** 查询主表是否为 community 本表（过滤列用 id 而非 community_id） */
    private boolean isCommunityTable(PlainSelect plain) {
        if (plain.getFromItem() instanceof Table table) {
            return "community".equalsIgnoreCase(table.getName());
        }
        return false;
    }
}
