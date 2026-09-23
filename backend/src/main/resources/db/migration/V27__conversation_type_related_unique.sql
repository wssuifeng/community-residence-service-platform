-- -----------------------------------------------------------------------------
-- V27: 会话「同类型同业务主体」唯一化（OCR 静态评审 DEF-106）
-- -----------------------------------------------------------------------------
-- 背景：三类业务群聊（VIEWING_GROUP / LEASE_GROUP / WORK_ORDER_GROUP）此前仅靠
-- 应用层「先查后插」防重复——两路并发（如租约审批内核与管理端 ensure 补建同时
-- 触发、工单受理重试与首建并发）可同时通过存在性检查各自 insert 成功，产生
-- 同 (type, related_id) 的重复会话；读侧一律 LIMIT 1 静默二选一，参与者与消息
-- 在两群间分裂（互相看不见对方发言，历史留档残缺）。
-- 修法（库级兜底 + 应用层配合）：
--   · 本迁移落唯一键 uk_conversation_type_related (type, related_id)；
--   · DIRECT 的 related_id 为 NULL——MySQL 唯一索引允许多行 NULL，互不影响
--     （DIRECT 幂等仍由应用层按「参与者 + 社区」判定，口径不变）；
--   · 应用层 createLeaseGroup / createWorkOrderGroup 已改为捕获
--     DuplicateKeyException 后重查返回既有会话（ConversationService，DEF-106）。
-- type VARCHAR(20) 参与索引无长度问题（V16 定义、V20 复核，最长取值
-- WORK_ORDER_GROUP 为 16 字符）。
--
-- 防御性去重（幂等保障）：两库当前均无重复行，以下归并段正常执行均为 0 行
-- 变更；若历史并发已产生重复，先归并到每组最小 id 保留行——参与者 INSERT
-- IGNORE 吸收 uk_cp_identity 撞键（口径同 V16 存量回填先例）、消息与看房预约
-- 回指改挂保留行（留痕不丢），再删重复会话行。保留行映射取自派生表
-- GROUP BY MIN(id)（而非自连接 keep.id < dup.id），三路及以上并发产生的
-- 重复也能归并到唯一保留行；派生表会被 MySQL 物化，不触发同表更新限制。
-- 诊断（只读，执行后人工核对应为 0 行）：
-- SELECT type, related_id, COUNT(*) FROM conversation
--  WHERE related_id IS NOT NULL GROUP BY type, related_id HAVING COUNT(*) > 1;
-- -----------------------------------------------------------------------------

/* 1. 参与者归并：重复行上的参与者改挂保留行（撞 uk_cp_identity 由 INSERT IGNORE 吸收） */
INSERT IGNORE INTO conversation_participant (conversation_id, owner_type, user_id, last_read_message_id, created_at)
SELECT m.keep_id, p.owner_type, p.user_id, p.last_read_message_id, p.created_at
FROM (SELECT type, related_id, MIN(id) AS keep_id
      FROM conversation
      WHERE related_id IS NOT NULL
      GROUP BY type, related_id
      HAVING COUNT(*) > 1) m
         JOIN conversation dup
              ON dup.type = m.type AND dup.related_id = m.related_id AND dup.id <> m.keep_id
         JOIN conversation_participant p ON p.conversation_id = dup.id;

/* 2. 删除重复行上已归并的参与者行（conversation_participant 对 conversation 有 FK，
      须先于重复会话行清理；目标表经别名 p 参与多表 DELETE，合法） */
DELETE p FROM conversation_participant p
        JOIN conversation dup ON dup.id = p.conversation_id
        JOIN (SELECT type, related_id, MIN(id) AS keep_id
              FROM conversation
              WHERE related_id IS NOT NULL
              GROUP BY type, related_id
              HAVING COUNT(*) > 1) m
             ON m.type = dup.type AND m.related_id = dup.related_id AND dup.id <> m.keep_id;

/* 3. 消息归并：重复行上的历史消息改挂保留行（只读留痕不丢；保留行的
      last_message/last_message_at 冗余列可能滞后，属展示口径，由后续发言自然刷新） */
UPDATE conversation_message msg
        JOIN conversation dup ON dup.id = msg.conversation_id
        JOIN (SELECT type, related_id, MIN(id) AS keep_id
              FROM conversation
              WHERE related_id IS NOT NULL
              GROUP BY type, related_id
              HAVING COUNT(*) > 1) m
             ON m.type = dup.type AND m.related_id = dup.related_id AND dup.id <> m.keep_id
SET msg.conversation_id = m.keep_id;

/* 4. 看房预约回指归并：viewing_appointment.conversation_id 对 conversation 有 FK
      （V16；会话内发起的看房预约经 R69 也可能指向 LEASE_GROUP 会话），指向重复行
      的须改指保留行后才能删除重复会话行（lease_record / work_order 的
      conversation_id 为弱关联无 FK，指向重复行属可接受的惰性孤儿，不处理） */
UPDATE viewing_appointment va
        JOIN conversation dup ON dup.id = va.conversation_id
        JOIN (SELECT type, related_id, MIN(id) AS keep_id
              FROM conversation
              WHERE related_id IS NOT NULL
              GROUP BY type, related_id
              HAVING COUNT(*) > 1) m
             ON m.type = dup.type AND m.related_id = dup.related_id AND dup.id <> m.keep_id
SET va.conversation_id = m.keep_id;

/* 5. 删除重复会话行（每组保留最小 id；派生表物化后连接，同表 DELETE 合法） */
DELETE dup FROM conversation dup
        JOIN (SELECT type, related_id, MIN(id) AS keep_id
              FROM conversation
              WHERE related_id IS NOT NULL
              GROUP BY type, related_id
              HAVING COUNT(*) > 1) m
             ON m.type = dup.type AND m.related_id = dup.related_id AND dup.id <> m.keep_id;

/* 6. 唯一键：同类型同业务主体至多一条会话（DIRECT 的 related_id 为 NULL 不受限） */
ALTER TABLE conversation
    ADD UNIQUE KEY uk_conversation_type_related (type, related_id);

-- -----------------------------------------------------------------------------
-- 校验（只读，人工核对）
-- -----------------------------------------------------------------------------
-- SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation' ORDER BY INDEX_NAME, SEQ_IN_INDEX;
-- 表数不变（仅加索引），业务表数仍 52。
