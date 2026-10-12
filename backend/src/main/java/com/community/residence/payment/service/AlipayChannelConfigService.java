package com.community.residence.payment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.constant.*;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.log.annotation.OperationLog;
import com.community.residence.payment.config.*;
import com.community.residence.payment.dto.UpdateAlipayChannelConfigDTO;
import com.community.residence.payment.entity.*;
import com.community.residence.payment.mapper.*;
import com.community.residence.payment.vo.AlipayChannelConfigVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AlipayChannelConfigService {
    private final PaymentChannelConfigMapper configMapper;
    private final LeasePaymentMapper paymentMapper;
    private final PaymentSecretCipher cipher;
    private final AlipayKeyValidator validator;

    public AlipayChannelConfigVO get() { requireSuperAdmin(); return toVO(read()); }

    /* 行锁 + 版本校验；先离线验证，再加密写入。空私钥保留，明确清除互斥，始终停用。 */
    @Transactional(rollbackFor = Exception.class)
    @OperationLog(operationType = "UPDATE", targetType = "PAYMENT_CHANNEL_CONFIG", targetId = "1", content = "'更新支付宝渠道配置（离线校验，交易停用）'")
    public AlipayChannelConfigVO save(UpdateAlipayChannelConfigDTO dto) {
        requireSuperAdmin();
        PaymentChannelConfig row = configMapper.lockAlipay();
        if (row == null) { throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "支付配置迁移尚未应用"); }
        if (!Objects.equals(row.getRevision(), dto.getExpectedRevision())) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID, "配置已被其他操作更新，请重新加载后保存");
        }
        if (dto.getAppId() == null || !dto.getAppId().matches("[0-9]{16,32}")) { throw invalid("APPID 必须为16~32位数字"); }
        PaymentEnvironment environment;
        try { environment = PaymentEnvironment.valueOf(dto.getEnvironment()); }
        catch (Exception e) { throw invalid("请选择沙箱或正式环境"); }
        boolean newPrivateKey = hasText(dto.getMerchantPrivateKey());
        if (dto.isClearPrivateKey() && newPrivateKey) { throw invalid("清除私钥与填写新私钥不能同时进行"); }
        boolean changingIdentity = hasText(row.getAppId()) && (!row.getAppId().equals(dto.getAppId()) || !row.getEnvironment().equals(environment.name()));
        if (changingIdentity && paymentMapper.selectCount(new LambdaQueryWrapper<LeasePayment>().eq(LeasePayment::getChannel, PaymentChannel.ALIPAY)) > 0) {
            throw invalid("已有支付宝支付记录，不能更换已登记的 APPID 或环境");
        }
        String oldPrivate = hasText(row.getMerchantPrivateKeyCiphertext())
                ? cipher.decrypt(row.getMerchantPrivateKeyCiphertext(), identity(row.getEnvironment(), row.getAppId())) : null;
        String secret = newPrivateKey ? dto.getMerchantPrivateKey() : oldPrivate;
        if (!dto.isClearPrivateKey() && !hasText(secret)) { throw invalid("首次配置需要填写应用私钥"); }
        /* 清除时仍用原私钥核对提交公钥，避免错误字段覆盖已有配置。 */
        if (dto.isClearPrivateKey() && !hasText(oldPrivate)) { throw invalid("尚未保存私钥，无需清除"); }
        var keys = validator.validate(dto.getApplicationPublicKey(), dto.isClearPrivateKey() ? oldPrivate : secret, dto.getAlipayPublicKey());
        row.setAppId(dto.getAppId()); row.setEnvironment(environment.name());
        row.setApplicationPublicKey(keys.applicationPublicKey()); row.setAlipayPublicKey(keys.alipayPublicKey());
        row.setMerchantPrivateKeyCiphertext(dto.isClearPrivateKey() ? null : cipher.encrypt(keys.privateKey(), identity(environment.name(), dto.getAppId())));
        row.setEnabled(false); row.setRevision(row.getRevision() + 1); row.setUpdatedBy(SecurityUtils.getUserId()); row.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(row);
        return toVO(row);
    }

    /** 内部客户端读取独立配置，无超管响应路径暴露明文；本批停用时不解密。 */
    public RuntimeConfig runtimeConfig() {
        PaymentChannelConfig row = read();
        if (!Boolean.TRUE.equals(row.getEnabled()) || !hasText(row.getMerchantPrivateKeyCiphertext())) { return null; }
        PaymentProperties properties = new PaymentProperties(); var alipay = properties.getAlipay();
        alipay.setAppId(row.getAppId()); alipay.setGatewayUrl(PaymentEnvironment.valueOf(row.getEnvironment()).alipayGateway());
        alipay.setAlipayPublicKey(row.getAlipayPublicKey());
        alipay.setMerchantPrivateKey(cipher.decrypt(row.getMerchantPrivateKeyCiphertext(), identity(row.getEnvironment(), row.getAppId())));
        return new RuntimeConfig(row.getRevision(), properties);
    }

    public record RuntimeConfig(long revision, PaymentProperties properties) {
        @Override public String toString() { return "RuntimeConfig[redacted]"; }
    }

    private PaymentChannelConfig read() {
        var row = configMapper.selectOne(new LambdaQueryWrapper<PaymentChannelConfig>().eq(PaymentChannelConfig::getChannel, PaymentChannel.ALIPAY));
        if (row == null) { throw new BusinessException(ErrorCode.DATA_NOT_FOUND, "支付配置迁移尚未应用"); }
        return row;
    }
    private AlipayChannelConfigVO toVO(PaymentChannelConfig row) {
        var vo = new AlipayChannelConfigVO(); vo.setAppId(row.getAppId()); vo.setEnvironment(row.getEnvironment());
        vo.setApplicationPublicKey(row.getApplicationPublicKey()); vo.setAlipayPublicKey(row.getAlipayPublicKey());
        vo.setPrivateKeyConfigured(hasText(row.getMerchantPrivateKeyCiphertext()));
        vo.setConfigured(vo.isPrivateKeyConfigured() && hasText(row.getAppId()) && hasText(row.getApplicationPublicKey()) && hasText(row.getAlipayPublicKey()));
        vo.setEnabled(false); vo.setRevision(row.getRevision()); vo.setUpdatedAt(row.getUpdatedAt()); return vo;
    }
    private void requireSuperAdmin() {
        if (!SecurityUtils.hasRole(RoleConstants.SUPER_ADMIN)) { throw new BusinessException(ErrorCode.FORBIDDEN); }
    }
    private String identity(String environment, String appId) { return PaymentChannel.ALIPAY + ":" + environment + ":" + appId; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAM, message); }
}
