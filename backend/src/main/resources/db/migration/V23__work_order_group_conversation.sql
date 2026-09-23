-- =============================================================================
-- Flyway Migration V23: Work Order Group Conversation (R74)
-- =============================================================================
-- Description: 工单完整链路（2026-09-22 用户裁决 R74；接口设计 v1.6 §9.21 /
--              数据库设计 v1.5 §9 第 17 条「V23 设计待派」）：
--   ① work_order 增列 conversation_id（工单临时群聊 conversation.id，
--      **弱关联不设外键**，口径同 lease_record.conversation_id（V20/R68））
--      + 索引 idx_wo_conversation (conversation_id)；
--   ② conversation.type 取值/注释扩展 WORK_ORDER_GROUP（工单临时群聊，
--      related_id = work_order.id）——列类型与默认值不变，仅扩注释取值。
--
-- 表数：**不变（仍 53）**——只加列/加索引/改注释，不新增表。
-- 生命周期：受理成功（PENDING → TO_ASSIGN，同一事务）建群 status=ACTIVE；
--           管理端终审关闭工单（COMPLETED → CLOSED）后置 CLOSED + closed_at，
--           历史只读留痕、**不物理删除**（复用 V20 既有 status/closed_at 两列）。
-- =============================================================================

/* 1. 工单 → 工单临时群聊（弱关联，不设外键：避免与工单归档/群聊回收互相阻断） */
ALTER TABLE work_order
    ADD COLUMN conversation_id BIGINT UNSIGNED NULL
        COMMENT '工单临时群聊ID（R74，conversation.id，弱关联不设外键）' AFTER priority,
    ADD INDEX idx_wo_conversation (conversation_id);

/* 2. 会话类型扩 WORK_ORDER_GROUP（工单临时群聊） */
ALTER TABLE conversation
    MODIFY COLUMN type VARCHAR(20) NOT NULL
        COMMENT '会话类型：VIEWING_GROUP-看房预约群聊, LEASE_GROUP-租约群聊, WORK_ORDER_GROUP-工单临时群聊（R74）, DIRECT-居民与管理员直通';

-- =============================================================================
-- 校验（只读，人工核对）
-- =============================================================================
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order' AND COLUMN_NAME = 'conversation_id';
-- SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'work_order' AND INDEX_NAME = 'idx_wo_conversation';
-- SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE();   -- 应为 53
