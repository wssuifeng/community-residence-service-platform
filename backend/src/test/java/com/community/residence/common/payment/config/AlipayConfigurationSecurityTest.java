package com.community.residence.common.payment.config;

import com.community.residence.common.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AlipayConfigurationSecurityTest {
    @TempDir Path dir;
    private KeyPair pair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); return g.generateKeyPair();
    }
    private String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }

    @Test void matchingKeysAcceptPemAndWhitespace() throws Exception {
        KeyPair app = pair(), platform = pair();
        var keys = new AlipayKeyValidator().validate("-----BEGIN PUBLIC KEY-----\n" + b64(app.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----",
                "-----BEGIN PRIVATE KEY-----\n" + b64(app.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----", b64(platform.getPublic().getEncoded()));
        assertEquals(b64(app.getPublic().getEncoded()), keys.applicationPublicKey());
        assertEquals(b64(app.getPrivate().getEncoded()), keys.privateKey());
    }
    @Test void mismatchedApplicationKeysAreRejectedWithoutEcho() throws Exception {
        KeyPair app = pair(), wrong = pair(), platform = pair();
        String secret = b64(wrong.getPrivate().getEncoded());
        var ex = assertThrows(BusinessException.class, () -> new AlipayKeyValidator().validate(b64(app.getPublic().getEncoded()), secret, b64(platform.getPublic().getEncoded())));
        assertFalse(ex.getMessage().contains(secret));
    }
    @Test void applicationPublicKeyCannotBeUsedAsPlatformKey() throws Exception {
        KeyPair app = pair();
        assertThrows(BusinessException.class, () -> new AlipayKeyValidator().validate(b64(app.getPublic().getEncoded()), b64(app.getPrivate().getEncoded()), b64(app.getPublic().getEncoded())));
    }
    @Test void invalidAndWeakKeysAreRejected() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(1024); var app = g.generateKeyPair(); var platform = pair();
        assertThrows(BusinessException.class, () -> new AlipayKeyValidator().validate(b64(app.getPublic().getEncoded()), b64(app.getPrivate().getEncoded()), b64(platform.getPublic().getEncoded())));
        assertThrows(BusinessException.class, () -> new AlipayKeyValidator().validate("bad", "PRIVATE-DATA", "bad"));
    }
    @Test void ciphertextRoundTripsWithRandomNonceAndIdentityBinding() {
        var cipher = new PaymentSecretCipher(dir.resolve("master.key").toString());
        String a = cipher.encrypt("test-only-private-material", "ALIPAY:SANDBOX:123");
        String b = cipher.encrypt("test-only-private-material", "ALIPAY:SANDBOX:123");
        assertNotEquals(a, b); assertFalse(a.contains("test-only-private-material"));
        assertEquals("test-only-private-material", cipher.decrypt(a, "ALIPAY:SANDBOX:123"));
        assertThrows(BusinessException.class, () -> cipher.decrypt(a, "ALIPAY:PRODUCTION:123"));
    }
    @Test void missingMasterKeyDoesNotSilentlyRegenerateOnDecrypt() throws Exception {
        Path file = dir.resolve("master.key"); var cipher = new PaymentSecretCipher(file.toString());
        String encrypted = cipher.encrypt("test-only", "identity"); Files.delete(file);
        assertThrows(BusinessException.class, () -> cipher.decrypt(encrypted, "identity"));
        assertFalse(Files.exists(file));
    }
    @Test void corruptMasterKeyAndCiphertextFailClosed() throws Exception {
        Path file = dir.resolve("master.key"); Files.writeString(file, "broken");
        var cipher = new PaymentSecretCipher(file.toString());
        assertThrows(BusinessException.class, () -> cipher.encrypt("test-only", "identity"));
        assertThrows(BusinessException.class, () -> cipher.decrypt("v1.invalid.invalid", "identity"));
    }
}
