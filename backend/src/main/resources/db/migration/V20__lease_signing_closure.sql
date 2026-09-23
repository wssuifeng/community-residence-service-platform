-- -----------------------------------------------------------------------------
-- V20: 租住签约闭环（R66~R71，需求规格 v1.8；决策日志 2026-09-21 五条）
-- -----------------------------------------------------------------------------
-- ① lease_record：状态机由 5 态扩为 6 态（新增「待签约」TO_SIGN）——审核通过只到
--    待签约（房屋不置已入住），租约协议双方确认（R64）才置已生效并翻转房屋状态；
--    新增承租确认留痕（tenant_confirm_at/by，R67，协议发起的前置条件）与
--    租约群聊关联列（conversation_id，R68，弱关联不设外键——避免与租约归档/
--    社区级联删除形成顺序耦合，口径同 viewing_appointment.lease_id）。
-- ② residence_application：状态机增「已撤回」WITHDRAWN（R66，待审核阶段居民可撤回）。
-- ③ conversation：类型增 LEASE_GROUP（related_id=lease_record.id），
--    新增会话生命周期状态 status/closed_at（R68：撤回/驳回/归档即关闭，停收停发只读留档）。
-- ④ lease_payment：新增支付单类型 payment_type（R71 首期 / R61 续期同表不同型），
--    幂等口径由「同租约一单进行中」收窄为「同租约同类型一单进行中」（应用层校验）。
-- ⑤ viewing_appointment：新增 lease_id（R69 会话内发起，与租约双向关联）——
--    会话内发起复用同一张表与同一状态机，仅多记归属租约。
-- ⑥ 新表 lease_move_out_application：居民自助退租申请（R70），
--    带 community_id → 必须纳入社区级联删除清单（DEF-066 纪律，数据库设计 §9）。
-- 列名对齐 V1 既有命名 created_at/updated_at；外键为 V1 式内联无名写法。
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 1. lease_record：状态注释更新 + 承租确认 + 租约群聊
-- -----------------------------------------------------------------------------
ALTER TABLE lease_record
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT '状态：PENDING-待审核, TO_SIGN-待签约, ACTIVE-已生效, MOVED_OUT-已搬出, ARCHIVED-已归档, REJECTED-已驳回',
    ADD COLUMN tenant_confirm_at DATETIME NULL COMMENT '承租确认时间（R67）' AFTER agreement_status,
    ADD COLUMN tenant_confirm_by BIGINT UNSIGNED NULL COMMENT '承租确认人（resident.id，R67）' AFTER tenant_confirm_at,
    ADD COLUMN conversation_id BIGINT UNSIGNED NULL COMMENT '租约群聊ID（R68，conversation.id，弱关联不设外键）' AFTER tenant_confirm_by,
    ADD INDEX idx_lease_conversation (conversation_id);

-- -----------------------------------------------------------------------------
-- 2. residence_application：状态注释补「已撤回」
-- -----------------------------------------------------------------------------
ALTER TABLE residence_application
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT '状态：PENDING-待审核, APPROVED-已通过, REJECTED-已驳回, WITHDRAWN-已撤回';

-- -----------------------------------------------------------------------------
-- 3. conversation：类型与关联语义注释更新 + 生命周期状态列
--    related_id 语义：VIEWING_GROUP=viewing_appointment.id；LEASE_GROUP=lease_record.id；DIRECT 空
-- -----------------------------------------------------------------------------
ALTER TABLE conversation
    MODIFY COLUMN type VARCHAR(20) NOT NULL
        COMMENT '会话类型：VIEWING_GROUP-看房预约群聊, LEASE_GROUP-租约群聊, DIRECT-居民与管理员直通',
    MODIFY COLUMN related_id BIGINT UNSIGNED NULL
        COMMENT '关联业务对象ID（VIEWING_GROUP=viewing_appointment.id；LEASE_GROUP=lease_record.id；DIRECT 空）',
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '会话状态（R68）：ACTIVE 进行中 / CLOSED 已关闭（只读留档）' AFTER related_id,
    ADD COLUMN closed_at DATETIME NULL COMMENT '会话关闭时间（R68）' AFTER status;

-- -----------------------------------------------------------------------------
-- 4. lease_payment：支付单类型（R71 首期 / R61 续期同表不同型）
--    存量单一律视为续期单（DEFAULT RENEWAL），口径不变。
-- -----------------------------------------------------------------------------
ALTER TABLE lease_payment
    ADD COLUMN payment_type VARCHAR(16) NOT NULL DEFAULT 'RENEWAL'
        COMMENT '支付单类型（R71）：FIRST 首期（首月租金+押金）/ RENEWAL 续期（月租×月数）'
        AFTER resident_id,
    ADD INDEX idx_lease_payment_type (lease_id, payment_type, status);

-- -----------------------------------------------------------------------------
-- 5. viewing_appointment：租约归属（R69 会话内发起看房预约）
--    不设外键：本列仅为双向关联标识，社区级联删除时预约先于租约清理（顺序见 CommunityService）。
-- -----------------------------------------------------------------------------
ALTER TABLE viewing_appointment
    ADD COLUMN lease_id BIGINT UNSIGNED NULL COMMENT '所属租约（R69，会话内发起；房源页发起为空）'
        AFTER conversation_id,
    ADD INDEX idx_va_lease (lease_id);

-- -----------------------------------------------------------------------------
-- 6. lease_move_out_application - 居民自助退租申请（R70）
--    状态机：PENDING → APPROVED / REJECTED；应用层约束同一租约同时仅一条 PENDING。
--    审核通过后联动：租约 ACTIVE→MOVED_OUT、居住关系终止、房屋置空置、房源状态回收。
--    community_id 为数据级权限锚点，级联删除清单必须纳入（DEF-066）。
--    业务表数 51 → 52（不含 flyway_schema_history）。
-- -----------------------------------------------------------------------------
CREATE TABLE lease_move_out_application (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '退租申请ID',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '申请人ID（resident.id）',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID（数据级权限锚点）',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '房屋ID',
    move_out_date DATE NOT NULL COMMENT '申请搬出日期',
    reason VARCHAR(500) NULL COMMENT '退租原因',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待审核, APPROVED-已通过, REJECTED-已驳回',
    reviewer_id BIGINT UNSIGNED NULL COMMENT '审核人用户ID',
    review_time DATETIME NULL COMMENT '审核时间',
    reject_reason VARCHAR(500) NULL COMMENT '驳回原因（驳回时必填）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_move_out_lease (lease_id, status),
    INDEX idx_move_out_community (community_id),
    INDEX idx_move_out_resident (resident_id),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居民自助退租申请表（R70）';
