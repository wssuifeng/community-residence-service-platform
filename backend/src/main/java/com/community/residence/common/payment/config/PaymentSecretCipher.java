package com.community.residence.common.payment.config;

import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 私钥 AES-256-GCM 存储加密；主密钥与数据库分离，仅保存在服务器受限文件中。 */
@Component
public class PaymentSecretCipher {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public PaymentSecretCipher(@Value("${payment.channel-config.key-file:${user.home}/.community-residence/payment/master.key}") String keyFile) {
        this.keyFile = Path.of(keyFile).toAbsolutePath().normalize();
    }

    public String encrypt(String plain, String identity) {
        try {
            byte[] nonce = new byte[NONCE_BYTES]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw unavailable(); }
    }

    public String decrypt(String encoded, String identity) {
        try {
            String[] parts = encoded.split("\\.", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) { throw unavailable(); }
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != NONCE_BYTES) { throw unavailable(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(identity.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw unavailable(); }
    }

    /* 解密不会创建新密钥；文件内容异常同样拒绝覆盖，防止静默丢失旧配置。 */
    private synchronized byte[] key(boolean allowCreate) throws Exception {
        for (Path part = keyFile; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) { throw unavailable(); }
        }
        if (!Files.exists(keyFile)) {
            if (!allowCreate) { throw unavailable(); }
            boolean newDirectory = !Files.exists(keyFile.getParent());
            Files.createDirectories(keyFile.getParent());
            if (newDirectory) { restrict(keyFile.getParent(), true); }
            byte[] bytes = new byte[KEY_BYTES]; random.nextBytes(bytes);
            try {
                Set<OpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                FileAttribute<?>[] attributes = Files.getFileAttributeView(keyFile.getParent(), PosixFileAttributeView.class) != null
                        ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))} : new FileAttribute<?>[0];
                try (var channel = Files.newByteChannel(keyFile, options, attributes)) {
                    restrict(keyFile, false);
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) { channel.write(buffer); }
                }
            } catch (FileAlreadyExistsException ignored) {
                /* 另一实例先创建时只读取其密钥，不覆盖。 */
            }
        }
        restrict(keyFile, false);
        byte[] bytes = Files.readAllBytes(keyFile);
        if (bytes.length != KEY_BYTES) { throw unavailable(); }
        return bytes;
    }

    private void restrict(Path path, boolean directory) throws Exception {
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------")); return; }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) { throw unavailable(); }
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "支付配置加密密钥不可用，请检查服务器密钥文件及备份；未覆盖已有私钥");
    }
}
