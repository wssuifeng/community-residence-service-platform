-- -----------------------------------------------------------------------------
-- V22: 押金状态与在线退还（R72，需求规格 v1.9 / 系统定义 v1.6；数据库设计 v1.5 §3.3.1 / §3.3.8）
-- -----------------------------------------------------------------------------
-- ① lease_record 增列 deposit_status（押金状态五态：UNPAID 未缴 / PAID 已缴 /
--    REFUNDING 退还中 / REFUNDED 已退还 / OFFSET 已抵扣）+ 索引 idx_lease_deposit_status
--    （管理端按押金状态筛选与统计）。流转由首期支付（R71）与押金退还（R72）驱动：
--    首期支付成功 → PAID；发起退款 → REFUNDING；渠道成功 → REFUNDED；
--    渠道失败 → 回落 PAID（可重试）；结算抵扣 → OFFSET（处置金额与说明在退款表留痕）。
-- ② 新表 lease_deposit_refund（押金退还记录，追加型留痕）：每次发起退还写一条新记录，
--    不覆盖历史；out_request_no 为商户退款请求号（唯一幂等键，同号重复提交返回既有结果）。
--    本表只承载【退款指令与渠道结果】，**不是资金账户**：平台不托管押金资金、不计息、
--    不垫付（系统定义 v1.6 §3 边界）——库内不得出现任何押金资金账户 / 余额 / 计息字段。
--    外键仅 lease_id → lease_record(id)；move_out_application_id 为弱关联（不设外键，
--    口径同 viewing_appointment.lease_id / lease_record.conversation_id）。
-- ③ 级联删除（DEF-066 纪律）：本表不带 community_id，但经 lease_id 关联租约——
--    社区级联删除时必须排在物理删除 lease_record 之前清理（CommunityService 同段处理）。
-- ④ 表数 52 → 53。
-- 列类型与命名对齐 V1 既有约定（ID 一律 BIGINT UNSIGNED；created_at/refunded_at）。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 1. lease_record：押金状态列 + 状态筛选索引
-- -----------------------------------------------------------------------------
ALTER TABLE lease_record
    ADD COLUMN deposit_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID'
        COMMENT '押金状态（R72）：UNPAID-未缴, PAID-已缴, REFUNDING-退还中, REFUNDED-已退还, OFFSET-已抵扣'
        AFTER deposit,
    ADD INDEX idx_lease_deposit_status (deposit_status);

-- -----------------------------------------------------------------------------
-- 2. lease_deposit_refund - 押金退还记录（追加型留痕，R72）
-- -----------------------------------------------------------------------------
CREATE TABLE lease_deposit_refund (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '押金退还记录ID',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    move_out_application_id BIGINT UNSIGNED NULL COMMENT '关联退租申请ID（lease_move_out_application.id，弱关联不设外键）',
    amount DECIMAL(10, 2) NOT NULL COMMENT '本次退还金额（≤ 该租约实缴押金，应用层校验）',
    channel VARCHAR(20) NOT NULL COMMENT '退款渠道：ALIPAY-支付宝, WECHAT-微信（必须与原支付渠道一致，跨渠道拒绝）',
    out_request_no VARCHAR(64) NOT NULL COMMENT '商户退款请求号（幂等键，同号重复提交返回既有结果）',
    channel_refund_no VARCHAR(64) NULL COMMENT '渠道退款单号（渠道返回后回写；SUCCESS 时非空）',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '退款状态：PENDING-处理中, SUCCESS-已退还, FAILED-失败（失败可换新请求号重试）',
    fail_reason VARCHAR(500) NULL COMMENT '失败原因（渠道返回或异常摘要，FAILED 时必填）',
    operator_id BIGINT UNSIGNED NOT NULL COMMENT '发起人（sys_user.id，社区管理员 / 超管）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    refunded_at DATETIME NULL COMMENT '退款完成时间（SUCCESS 时写）',
    UNIQUE KEY uk_deposit_out_request_no (out_request_no),
    INDEX idx_deposit_lease (lease_id, created_at),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='押金退还记录表（R72 追加型留痕，非资金账户）';
