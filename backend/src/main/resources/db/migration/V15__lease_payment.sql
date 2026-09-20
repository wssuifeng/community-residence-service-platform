-- -----------------------------------------------------------------------------
-- V15: 租约续约支付单（R61，需求规格 v1.4；决策日志 2026-09-20 条目）
-- -----------------------------------------------------------------------------
-- 新表 lease_payment：居民在线续租支付单（支付宝电脑网站支付 / 微信 Native 扫码）。
-- payment_no 业务单号 RENEW+yyyyMMdd+6位随机（对齐工单号风格，应用层撞号重试）。
-- 幂等口径：同 lease_id 仅允许一个进行中（PENDING）支付单，应用层校验
-- （MySQL 不支持部分唯一索引，不建库级约束）；金额服务端按月租×月数计算，
-- 客户端不可传金额。状态机：PENDING → SUCCESS / CLOSED（终态）。
-- 列名对齐 V1 既有命名 created_at/updated_at；外键为 V1 式内联无名写法。

CREATE TABLE lease_payment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '支付单ID',
    payment_no VARCHAR(64) NOT NULL COMMENT '业务单号（RENEW+yyyyMMdd+6位随机）',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '付款人ID（resident.id）',
    channel VARCHAR(16) NOT NULL COMMENT '支付渠道：ALIPAY-支付宝, WECHAT-微信',
    months INT NOT NULL COMMENT '续租月数',
    amount DECIMAL(10, 2) NOT NULL COMMENT '应付金额（月租×月数，服务端计算）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待支付, SUCCESS-已支付, CLOSED-已关闭',
    channel_trade_no VARCHAR(64) NULL COMMENT '渠道流水号（支付成功后回写）',
    paid_at DATETIME NULL COMMENT '支付完成时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_payment_no (payment_no),
    INDEX idx_lease_id (lease_id),
    INDEX idx_resident_id (resident_id),
    INDEX idx_status (status),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id),
    FOREIGN KEY (resident_id) REFERENCES resident(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租约续约支付单表（R61）';
