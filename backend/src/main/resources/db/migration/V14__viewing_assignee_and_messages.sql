-- -----------------------------------------------------------------------------
-- V14: 带看人分配与带看沟通会话（R59，需求规格 v1.3；决策日志 2026-09-20 条目）
-- -----------------------------------------------------------------------------
-- ① viewing_appointment 增列 assigned_staff_id：管理端将看房预约分配给带看人
--    （启用状态的 STAFF/ADMIN 账号），NULL=未分配；重复分配允许覆盖（换带看人）。
-- ② 新表 viewing_message：预约居民与带看人的会话消息（WS 实时推送 +
--    HTTP 轮询兜底，复制 R30 反馈会话模式，主题 /topic/appointment/{id}）。
-- sender_id 为多态引用（预约居民=resident.id，带看人=sys_user.id），不设外键——
-- 口径同 V1 feedback_message.sender_id 先例（居民不在 sys_user 表，单表外键无法表达）。
-- 列名对齐 V1 既有命名：created_at（非 create_time）；外键为 V1 式内联无名写法。

ALTER TABLE viewing_appointment
    ADD COLUMN assigned_staff_id BIGINT UNSIGNED NULL COMMENT '带看人ID（sys_user.id，NULL=未分配；R59）' AFTER visitor_phone,
    ADD FOREIGN KEY (assigned_staff_id) REFERENCES sys_user(id);

CREATE TABLE viewing_message (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    appointment_id BIGINT UNSIGNED NOT NULL COMMENT '看房预约ID',
    sender_id BIGINT UNSIGNED NOT NULL COMMENT '发送人ID（预约居民=resident.id，带看人=sys_user.id，多态引用不设外键）',
    content VARCHAR(500) NOT NULL COMMENT '消息内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX idx_appointment_id (appointment_id),
    FOREIGN KEY (appointment_id) REFERENCES viewing_appointment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='看房沟通会话消息表（R59）';
