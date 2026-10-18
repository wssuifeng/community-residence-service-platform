package com.community.residence.common.payment.config;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** RSA2 离线校验；不调用支付宝，不能证明平台侧 APPID/公钥的真实归属。 */
@Component
public class AlipayKeyValidator {
    public record Keys(String applicationPublicKey, String privateKey, String alipayPublicKey) {
        @Override public String toString() { return "Keys[redacted]"; }
    }

    /* 只接受标准 RSA/X.509/PKCS8，不猜测或自动修复密钥内容。 */
    public Keys validate(String appPublic, String privateKey, String platformPublic) {
        try {
            String app = normalize(appPublic, "PUBLIC KEY");
            String secret = normalize(privateKey, "PRIVATE KEY");
            String platform = normalize(platformPublic, "PUBLIC KEY");
            KeyFactory factory = KeyFactory.getInstance("RSA");
            var publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(app)));
            var signingKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(secret)));
            var alipayKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(platform)));
            for (var key : new java.security.Key[]{publicKey, signingKey, alipayKey}) {
                if (!(key instanceof RSAKey rsa) || rsa.getModulus().bitLength() < 2048) {
                    throw invalid("RSA2 密钥必须为 RSA 2048 位或以上");
                }
            }
            if (java.util.Arrays.equals(publicKey.getEncoded(), alipayKey.getEncoded())) {
                throw invalid("支付宝公钥不能使用应用公钥，请从开放平台获取");
            }
            byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signingKey); signature.update(challenge); byte[] signed = signature.sign();
            signature.initVerify(publicKey); signature.update(challenge);
            if (!signature.verify(signed)) { throw invalid("应用公钥与应用私钥不匹配"); }
            return new Keys(app, secret, platform);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("密钥格式不正确：公钥需 X.509，应用私钥需 PKCS8，请核对三份 Key 的用途");
        }
    }

    private String normalize(String key, String label) {
        if (key == null || key.isBlank() || key.length() > 8192) { throw invalid("请填写有效的密钥内容"); }
        String value = key.trim();
        if (value.startsWith("-----BEGIN")) {
            String begin = "-----BEGIN " + label + "-----", end = "-----END " + label + "-----";
            if (!value.startsWith(begin) || !value.endsWith(end)) { throw invalid("密钥 PEM 类型不正确"); }
            value = value.substring(begin.length(), value.length() - end.length());
        }
        return value.replaceAll("\\s", "");
    }

    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAM, message); }
}
