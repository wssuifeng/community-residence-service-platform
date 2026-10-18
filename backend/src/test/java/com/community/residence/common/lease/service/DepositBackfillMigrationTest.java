package com.community.residence.common.lease.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V25 存量押金状态回填脚本守卫（R72 数据修补）：
 * 脚本必须①只对**成功首期单**且**押金部分 > 0**（单额 > 月租）的租约回填 PAID；
 * ②仅当当前状态为 UNPAID 时才回填（幂等，不回退退还中/已退还/已抵扣）；
 * ③押金为 0 与未收款租约不得被置为已缴。任一条件被改动即失败。
 */
@DisplayName("V25 押金状态回填脚本守卫")
class DepositBackfillMigrationTest {

    private static final String SCRIPT = "/db/migration/V25__lease_deposit_backfill.sql";

    private String readScript() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SCRIPT)) {
            assertThat(in).as("V25 回填脚本必须存在于 classpath: " + SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("回填规则：仅成功首期单且含押金部分（单额 > 月租）的租约置 PAID")
    void backfillRule_onlyPaidFirstPaymentWithDeposit() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("UPDATE lease_record");
        assertThat(sql).contains("SET l.deposit_status = 'PAID'");
        assertThat(sql).contains("p.payment_type = 'FIRST'");
        assertThat(sql).contains("p.status = 'SUCCESS'");
        /* 押金部分 = 单额 − 月租 > 0：押金为 0 的租约不得置已缴 */
        assertThat(sql).contains("p.amount > l.monthly_rent");
    }

    @Test
    @DisplayName("幂等：仅 UNPAID 才回填（已缴/退还中/已退还/已抵扣不被回退或覆盖）")
    void backfillIsIdempotent() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("WHERE l.deposit_status = 'UNPAID'");
        assertThat(sql).doesNotContain("deposit_status = 'REFUNDED'");
        assertThat(sql).doesNotContain("deposit_status = 'OFFSET'");
    }
}
