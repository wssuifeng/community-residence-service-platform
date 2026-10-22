package com.community.residence.common.resident.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V26 迁移脚本守卫（入住申请 → 租约 强关联，DEF-080）：
 * ①只加一列 + 一索引（表数不变），列可空（无租约的 OWNER/FAMILY 申请、存量无法判定行必须能留 NULL）；
 * ②存量回填**仅** `status='APPROVED'`，取弱关联口径的最新一条租约，且仅在 `lease_id IS NULL` 时执行（幂等）；
 * ③脚本不得触碰其它表结构（V22~V25 的历史结构变更不得被本脚本改写）。
 */
@DisplayName("V26 入住申请-租约强关联脚本守卫")
class ResidenceApplicationLeaseIdMigrationTest {

    private static final String SCRIPT = "/db/migration/V26__residence_application_lease_link.sql";

    private String readScript() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SCRIPT)) {
            assertThat(in).as("V26 迁移脚本必须存在于 classpath: " + SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("结构变更：residence_application 加可空 lease_id + idx_app_lease（表数不变）")
    void structureAddsNullableLeaseIdAndIndex() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("ALTER TABLE residence_application");
        assertThat(sql).contains("ADD COLUMN lease_id BIGINT UNSIGNED NULL");
        assertThat(sql).contains("ADD KEY idx_app_lease (lease_id)");
        /* 不得新增表、不得改动其它业务表结构 */
        assertThat(sql).doesNotContain("CREATE TABLE");
        assertThat(sql).doesNotContain("ALTER TABLE lease_record");
        assertThat(sql).doesNotContain("ALTER TABLE lease_payment");
        assertThat(sql).doesNotContain("ALTER TABLE conversation");
    }

    @Test
    @DisplayName("回填规则：仅 APPROVED + 弱关联最新租约 + 仅 NULL 行（幂等、不猜第二遍）")
    void backfillRule_onlyApprovedAndBestEffort() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("UPDATE residence_application");
        assertThat(sql).contains("WHERE a.status = 'APPROVED'");
        assertThat(sql).contains("AND a.lease_id IS NULL");
        /* 弱关联口径：同 居民/小区/房屋，取最新（与 V26 前运行时代码口径一致） */
        assertThat(sql).contains("l.tenant_id = a.resident_id");
        assertThat(sql).contains("l.community_id = a.community_id");
        assertThat(sql).contains("l.house_id = a.house_id");
        assertThat(sql).contains("ORDER BY l.id DESC");
        assertThat(sql).contains("LIMIT 1");
        /* 命中不到的行保持 NULL：回填以 EXISTS 兜底，不写默认值、不写 0 */
        assertThat(sql).contains("EXISTS (SELECT 1");
        assertThat(sql).doesNotContain("lease_id = 0");
        /* 尽力回填声明必须写在脚本里（避免后人误当精确口径） */
        assertThat(sql).contains("尽力回填");
    }

    @Test
    @DisplayName("脚本自带校验 SQL（回填结果 / 未回填 / 悬空关联），便于人工复核")
    void scriptProvidesVerificationQueries() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("-- SELECT id, status, resident_id, community_id, house_id, lease_id");
        assertThat(sql).contains("WHERE a.status = 'APPROVED' AND a.lease_id IS NULL");
        assertThat(sql).contains("LEFT JOIN lease_record l ON l.id = a.lease_id");
    }
}
