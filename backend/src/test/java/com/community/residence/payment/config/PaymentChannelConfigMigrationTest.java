package com.community.residence.payment.config;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.community.residence.payment.entity.PaymentChannelConfig;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class PaymentChannelConfigMigrationTest {
    @Test void separateTableStartsDisabledAndContainsNoSeedCredentials() throws Exception {
        try (var in = getClass().getResourceAsStream("/db/migration/V30__payment_channel_config.sql")) {
            assertNotNull(in); String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE payment_channel_config"));
            assertTrue(sql.contains("enabled TINYINT(1) NOT NULL DEFAULT 0"));
            assertTrue(sql.contains("INSERT INTO payment_channel_config (channel) VALUES ('ALIPAY')"));
            assertFalse(sql.contains("ALTER TABLE")); assertFalse(sql.contains("PRIVATE KEY"));
        }
    }
    @Test void privateKeyClearingUsesExplicitNullUpdate() throws Exception {
        var field = PaymentChannelConfig.class.getDeclaredField("merchantPrivateKeyCiphertext");
        assertEquals(FieldStrategy.ALWAYS, field.getAnnotation(TableField.class).updateStrategy());
    }
    @Test void globalConfigDoesNotReceiveCommunityColumnFilter() throws Exception {
        var field = com.community.residence.interceptor.DataScopeInterceptor.class.getDeclaredField("SKIP_TABLES");
        field.setAccessible(true);
        assertTrue(((java.util.Set<?>) field.get(null)).contains("payment_channel_config"));
    }
    @Test void masterKeyPathDefaultsOutsideRepository() throws Exception {
        try (var in = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(in); String yaml = new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(yaml.contains("${user.home}/.community-residence/payment/master.key"));
            assertFalse(yaml.contains("${PAYMENT_ALIPAY_MERCHANT_PRIVATE_KEY"));
            assertFalse(yaml.contains("${PAYMENT_ALIPAY_APP_ID"));
        }
    }
}
