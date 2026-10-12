-- R61：独立支付宝渠道配置，私钥密文与服务器主密钥分离，不迁移环境变量凭据。
CREATE TABLE payment_channel_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    channel VARCHAR(16) NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'SANDBOX',
    app_id VARCHAR(32) NULL,
    application_public_key TEXT NULL,
    merchant_private_key_ciphertext TEXT NULL,
    alipay_public_key TEXT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_channel_config_channel (channel),
    CONSTRAINT ck_payment_channel_config_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO payment_channel_config (channel) VALUES ('ALIPAY');
