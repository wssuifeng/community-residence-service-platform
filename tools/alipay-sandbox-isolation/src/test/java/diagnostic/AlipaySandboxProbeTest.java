package diagnostic;

import com.alipay.api.domain.AlipayTradePagePayModel;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlipaySandboxProbeTest {
    private Map<String, String> credentials() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Map<String, String> env = new HashMap<>();
        env.put("ALIPAY_PROBE_APP_ID", "9021000000000000");
        env.put("ALIPAY_PROBE_PRIVATE_KEY", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        env.put("ALIPAY_PROBE_PUBLIC_KEY", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        return env;
    }

    @Test void rejectsProductionGateway() throws Exception {
        var env = credentials();
        env.put("ALIPAY_PROBE_GATEWAY", "https://openapi.alipay.com/gateway.do");
        assertThrows(IllegalArgumentException.class, () -> AlipaySandboxProbe.credentials(env));
    }

    @Test void rejectsHttpGateway() throws Exception {
        var env = credentials();
        env.put("ALIPAY_PROBE_GATEWAY", "http://openapi-sandbox.dl.alipaydev.com/gateway.do");
        assertThrows(IllegalArgumentException.class, () -> AlipaySandboxProbe.credentials(env));
    }

    @Test void rejectsMissingCredentials() {
        assertThrows(IllegalArgumentException.class, () -> AlipaySandboxProbe.credentials(Map.of()));
    }

    @Test void generatesFreshIndependentOrders() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 100; i++) {
            String no = AlipaySandboxProbe.newOrderNo();
            assertTrue(no.matches("ISO[A-Z0-9]{10,40}"));
            assertTrue(seen.add(no));
            assertTrue(no.length() <= 64);
        }
    }

    @Test void onlyAcceptsIndependentQueryOrderPrefix() {
        assertFalse(AlipaySandboxProbe.isDiagnosticOrder("FIRST20261010919691"));
        assertFalse(AlipaySandboxProbe.isDiagnosticOrder("RENEW20261010000000"));
        assertTrue(AlipaySandboxProbe.isDiagnosticOrder(AlipaySandboxProbe.newOrderNo()));
    }

    @Test void usesOnlyDocumentedRequiredBusinessFields() {
        var request = AlipaySandboxProbe.payRequest(AlipaySandboxProbe.newOrderNo());
        var model = (AlipayTradePagePayModel) request.getBizModel();
        assertEquals("0.01", model.getTotalAmount());
        assertEquals("FAST_INSTANT_TRADE_PAY", model.getProductCode());
        assertEquals("Alipay sandbox isolated test", model.getSubject());
        assertNotNull(model.getOutTradeNo());
        assertNull(request.getNotifyUrl());
        assertNull(request.getReturnUrl());
    }

    @Test void officialSdkProducesNativePostForm() throws Exception {
        var client = AlipaySandboxProbe.client(AlipaySandboxProbe.credentials(credentials()));
        String form = client.pageExecute(AlipaySandboxProbe.payRequest(AlipaySandboxProbe.newOrderNo())).getBody();
        assertTrue(form.contains("method=\"post\""));
        assertTrue(form.contains(AlipaySandboxProbe.SANDBOX_GATEWAY));
        assertTrue(form.contains("name=\"biz_content\""));
        assertFalse(form.contains("blob:"));
        assertFalse(form.contains("window.open"));
    }

    @Test void officialSdkGetSignatureValidatesAndContainsBizContent() throws Exception {
        var env = credentials();
        var client = AlipaySandboxProbe.client(AlipaySandboxProbe.credentials(env));
        String url = client.pageExecute(AlipaySandboxProbe.payRequest(AlipaySandboxProbe.newOrderNo()), "GET").getBody();
        var params = new TreeMap<String, String>();
        for (String entry : URI.create(url).getRawQuery().split("&")) {
            String[] parts = entry.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        String sign = params.remove("sign");
        assertNotNull(params.get("biz_content"));
        assertEquals("RSA2", params.get("sign_type"));
        String canonical = params.entrySet().stream().filter(e -> !e.getValue().isEmpty())
                .map(e -> e.getKey() + "=" + e.getValue()).collect(java.util.stream.Collectors.joining("&"));
        var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(env.get("ALIPAY_PROBE_PUBLIC_KEY"))));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(sign)));
    }
}
