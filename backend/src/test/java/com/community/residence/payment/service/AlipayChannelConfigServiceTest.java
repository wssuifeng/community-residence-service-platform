package com.community.residence.payment.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.*;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.payment.config.*;
import com.community.residence.payment.dto.UpdateAlipayChannelConfigDTO;
import com.community.residence.payment.entity.PaymentChannelConfig;
import com.community.residence.payment.mapper.*;
import com.community.residence.payment.service.channel.ManagedAlipayChannelClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlipayChannelConfigServiceTest {
    @TempDir Path dir;
    PaymentChannelConfigMapper configs; LeasePaymentMapper payments; PaymentChannelConfig row;
    AlipayChannelConfigService service; UpdateAlipayChannelConfigDTO dto; Path keyFile;
    @BeforeEach void setup() throws Exception {
        SecurityUtils.setAuthentication(new UserContext(1L, "superadmin", "SUPER_ADMIN", Set.of()));
        configs = mock(PaymentChannelConfigMapper.class); payments = mock(LeasePaymentMapper.class);
        row = new PaymentChannelConfig(); row.setId(1L); row.setChannel("ALIPAY"); row.setEnvironment("SANDBOX"); row.setEnabled(false); row.setRevision(0L);
        when(configs.lockAlipay()).thenAnswer(i -> row); when(configs.selectOne(any())).thenAnswer(i -> row);
        when(configs.updateById(any(PaymentChannelConfig.class))).thenReturn(1); when(payments.selectCount(any())).thenReturn(0L);
        keyFile = dir.resolve("master.key"); service = new AlipayChannelConfigService(configs, payments, new PaymentSecretCipher(keyFile.toString()), new AlipayKeyValidator());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); var app = g.generateKeyPair(); var platform = g.generateKeyPair();
        dto = new UpdateAlipayChannelConfigDTO(); dto.setAppId("9021000000000000"); dto.setEnvironment("SANDBOX"); dto.setExpectedRevision(0L);
        dto.setApplicationPublicKey(Base64.getEncoder().encodeToString(app.getPublic().getEncoded()));
        dto.setMerchantPrivateKey(Base64.getEncoder().encodeToString(app.getPrivate().getEncoded()));
        dto.setAlipayPublicKey(Base64.getEncoder().encodeToString(platform.getPublic().getEncoded()));
    }
    @AfterEach void clear() { SecurityUtils.clear(); }
    @Test void savesAllFourFieldsEncryptedAndNeverEnablesTransactions() {
        String secret = dto.getMerchantPrivateKey(); var response = service.save(dto);
        assertTrue(response.isConfigured()); assertTrue(response.isPrivateKeyConfigured()); assertFalse(response.isEnabled());
        assertFalse(row.getEnabled()); assertEquals(1L, response.getRevision()); assertNotEquals(secret, row.getMerchantPrivateKeyCiphertext());
        assertFalse(row.toString().contains(secret)); assertFalse(dto.toString().contains(secret));
        assertNull(service.runtimeConfig());
    }
    @Test void blankPrivateKeyPreservesExistingKeyButReencryptsWithNewNonce() {
        service.save(dto); String oldCipher = row.getMerchantPrivateKeyCiphertext(); dto.setExpectedRevision(1L); dto.setMerchantPrivateKey("  ");
        assertTrue(service.save(dto).isPrivateKeyConfigured()); assertNotEquals(oldCipher, row.getMerchantPrivateKeyCiphertext()); assertEquals(2L, row.getRevision());
    }
    @Test void clearPrivateKeyRemovesCiphertextAndLeavesChannelDisabled() {
        service.save(dto); dto.setExpectedRevision(1L); dto.setMerchantPrivateKey(""); dto.setClearPrivateKey(true);
        var response = service.save(dto); assertFalse(response.isConfigured()); assertFalse(response.isPrivateKeyConfigured()); assertNull(row.getMerchantPrivateKeyCiphertext());
    }
    @Test void initialSaveRequiresPrivateKey() {
        dto.setMerchantPrivateKey(""); assertThrows(BusinessException.class, () -> service.save(dto)); verify(configs, never()).updateById(any(PaymentChannelConfig.class));
    }
    @Test void staleRevisionRejectsBeforeAnyKeyWrite() {
        dto.setExpectedRevision(99L); var e = assertThrows(BusinessException.class, () -> service.save(dto));
        assertEquals(ErrorCode.STATE_TRANSITION_INVALID,e.getErrorCode()); assertFalse(Files.exists(keyFile));
    }
    @Test void clearAndReplacementCannotBeCombined() {
        dto.setClearPrivateKey(true); assertThrows(BusinessException.class, () -> service.save(dto)); assertFalse(Files.exists(keyFile));
    }
    @Test void invalidEnvironmentAndAppIdRejectWithoutPersisting() {
        dto.setEnvironment("http://untrusted"); assertThrows(BusinessException.class, () -> service.save(dto));
        dto.setEnvironment("SANDBOX"); dto.setAppId("not-an-app-id"); assertThrows(BusinessException.class, () -> service.save(dto)); verify(configs,never()).updateById(any(PaymentChannelConfig.class));
    }
    @Test void existingPaymentsPreventChangingRegisteredIdentity() {
        service.save(dto); when(payments.selectCount(any())).thenReturn(1L); dto.setExpectedRevision(1L); dto.setEnvironment("PRODUCTION");
        assertThrows(BusinessException.class, () -> service.save(dto)); assertEquals("SANDBOX",row.getEnvironment());
        dto.setEnvironment("SANDBOX"); dto.setAppId("9021000000000001"); assertThrows(BusinessException.class, () -> service.save(dto));
    }
    @Test void missingKeyFileRejectsEvenReplacementOfExistingCiphertext() throws Exception {
        service.save(dto); String oldCipher = row.getMerchantPrivateKeyCiphertext(); Files.delete(keyFile); dto.setExpectedRevision(1L);
        assertThrows(BusinessException.class, () -> service.save(dto)); assertEquals(oldCipher,row.getMerchantPrivateKeyCiphertext()); assertFalse(Files.exists(keyFile));
    }
    @Test void allNonSuperAdminRolesCannotReadOrWrite() {
        for (String role : List.of("ADMIN","STAFF","RESIDENT")) {
            SecurityUtils.setAuthentication(new UserContext(2L,"other",role,Set.of()));
            assertThrows(BusinessException.class, () -> service.get()); assertThrows(BusinessException.class, () -> service.save(dto));
        }
        SecurityUtils.clear(); assertThrows(BusinessException.class, () -> service.get()); verify(configs, never()).lockAlipay();
    }
    @Test void responseAndDtoSerializationDoNotExposePrivateMaterial() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build(); String secret=dto.getMerchantPrivateKey();
        assertFalse(mapper.writeValueAsString(dto).contains(secret));
        service.save(dto); String json=mapper.writeValueAsString(service.get());
        assertFalse(json.contains(secret)); assertFalse(json.contains(row.getMerchantPrivateKeyCiphertext())); assertFalse(json.contains("merchantPrivateKey"));
    }
    @Test void disabledManagedClientDoesNotBuildOrCallSdk() {
        var config = mock(AlipayChannelConfigService.class); var managed = new ManagedAlipayChannelClient(config);
        assertFalse(managed.enabled()); verify(config).runtimeConfig();
        assertThrows(BusinessException.class, () -> managed.queryTrade("TEST_ONLY"));
    }
}
