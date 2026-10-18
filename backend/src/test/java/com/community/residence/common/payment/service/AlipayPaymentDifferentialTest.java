package com.community.residence.common.payment.service;

import com.community.residence.common.payment.config.PaymentProperties;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.service.channel.AlipayChannelClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 对照实际首期/租金业务快照，执行生产标题与SDK生成实现；仅合成密钥及本机空端口，不访问渠道。 */
class AlipayPaymentDifferentialTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void firstAndRentUseActualProductionFormGenerationWithOnlyBusinessFieldDifferences() throws Exception {
        var keygen = KeyPairGenerator.getInstance("RSA");
        keygen.initialize(2048);
        var pair = keygen.generateKeyPair();
        var props = new PaymentProperties();
        props.getAlipay().setAppId("9021000000000000");
        props.getAlipay().setGatewayUrl("http://127.0.0.1:9/gateway.do");
        props.getAlipay().setMerchantPrivateKey(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        props.getAlipay().setAlipayPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var client = new AlipayChannelClient(props);
        var service = new PaymentService(null, null, null, null, props, client, null);
        String fixturePath = System.getProperty("cb.payment.fixtures");
        var rows = fixturePath == null ? json.readTree("[{\"purpose\":\"FIRST\",\"months\":1,\"paymentNo\":\"FIRST20261015000001\",\"amount\":14200.00,\"channelExpiresAt\":\"2026-10-16 00:00:00\"},{\"purpose\":\"RENT\",\"months\":1,\"paymentNo\":\"RENEW20261015000002\",\"amount\":3600.00,\"channelExpiresAt\":\"2026-10-16 20:37:20\"}]")
                : json.readTree(Files.readString(Path.of(fixturePath)));
        Path output = fixturePath == null ? null : Path.of(System.getProperty("cb.payment.output"));
        if (output != null) Files.createDirectories(output);
        List<Map<String,Object>> cases = new ArrayList<>();
        int index = 0;
        for (var row : rows) {
            var order = new LeasePayment();
            order.setPaymentType(row.path("purpose").asText());
            order.setMonths(row.path("months").asInt());
            order.setPaymentNo(row.path("paymentNo").asText());
            order.setAmount(new BigDecimal(row.path("amount").asText()).setScale(2));
            order.setChannel("ALIPAY");
            order.setStatus("PENDING");
            String deadline = row.path("channelExpiresAt").asText().substring(0,19);
            order.setChannelExpiresAt(LocalDateTime.parse(deadline, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            String subject = ReflectionTestUtils.invokeMethod(service,"subjectOf",order);
            assertThat(subject).doesNotContain("/", "=", "&");
            assertThat(order.getPaymentNo()).matches("[A-Za-z0-9_]{1,64}");
            /* 在脱离数据库的旧快照上还原当时PENDING及发起时间，只对当前内存样本生效。 */
            var generationTime = row.has("createdAt")
                    ? LocalDateTime.parse(row.path("createdAt").asText().substring(0,19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).plusSeconds(30)
                    : order.getChannelExpiresAt().minusMinutes(2);
            String html;
            try (var clock = org.mockito.Mockito.mockStatic(LocalDateTime.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
                clock.when(LocalDateTime::now).thenReturn(generationTime);
                html = ReflectionTestUtils.invokeMethod(service,"credentialOf",order);
            }
            assertThat(html).contains("alipay.trade.page.pay", "biz_content", "post", "FAST_INSTANT_TRADE_PAY", "UTF-8", "RSA2");
            if (output != null) Files.writeString(output.resolve("case-" + index + ".html"),html);
            cases.add(Map.of("index",index++,"purpose",order.getPaymentType(),"months",order.getMonths(),"amount",order.getAmount(),"subject",subject,"timeExpire",deadline,"outTradeNo",order.getPaymentNo()));
        }
        assertThat(cases).hasSizeGreaterThanOrEqualTo(2);
        if (output != null) Files.writeString(output.resolve("cases-private.json"),json.writeValueAsString(cases));
    }
}
