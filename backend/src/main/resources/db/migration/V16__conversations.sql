-- -----------------------------------------------------------------------------
-- V16: 多方会话（R63，需求规格 v1.5；决策日志 2026-09-20 条目）
-- -----------------------------------------------------------------------------
-- ① 新表 conversation / conversation_participant / conversation_message：
--    会话/参与者/消息三级模型，取代 R59 viewing_message 单聊口径
--    （viewing_message 与旧消息端点保留兼容，后端标注迁移注释）。
--    type：VIEWING_GROUP-看房预约群聊（related_id=viewing_appointment.id）、
--    DIRECT-居民与社区管理员直通（related_id 空，幂等由应用层按参与者+社区判定）。
--    参与者/发送人 user_id 为多态引用（居民=resident.id，
--    STAFF/ADMIN/SUPER_ADMIN=sys_user.id），不设外键——口径同 V1
--    feedback_message.sender_id 先例（居民不在 sys_user 表，单表外键无法表达）。
--    未读口径：unread = COUNT(message.id > participant.last_read_message_id)，
--    游标 0 表示全部未读；last_message/last_message_at 冗余列由发消息时同事务维护；
--    message.sender_role 落库时取认证角色，读侧按角色选表回姓名，消解
--    resident.id 与 sys_user.id 两套自增序列的撞号歧义。
-- ② viewing_appointment 增列 conversation_id：看房预约关联群聊
--    （R59 单聊模型废弃迁移）。
-- ③ 存量回填：每个 viewing_appointment 建 VIEWING_GROUP
--    （title=「看房沟通·{housingTitle}」），预约居民（user_id 非空时）+
--    该社区启用 ADMIN（sys_admin_community 绑定且 sys_user 启用）+
--    assigned_staff_id（非空时）入群，回填 conversation_id。
--    参与者插入用 INSERT IGNORE 吸收撞号（同一人既是社区管理员又是带看人、
--    或多态两套自增序列同号）——重复参与者行仅保留先入的一条。
-- conversation.community_id 不设外键：口径同 V1 notification 先例
-- （社区级联删除（R1/R6 v1.1）不清理会话数据，留惰性孤儿不阻断删除）。
-- 列名对齐 V1 既有命名 created_at；外键为 V1 式内联无名写法。

CREATE TABLE conversation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    type VARCHAR(20) NOT NULL COMMENT '会话类型：VIEWING_GROUP-看房预约群聊, DIRECT-居民与管理员直通',
    title VARCHAR(200) NOT NULL COMMENT '会话标题',
    community_id BIGINT UNSIGNED NULL COMMENT '所属社区ID（不设外键，口径同 notification；级联删除留孤儿不阻断）',
    related_id BIGINT UNSIGNED NULL COMMENT '关联业务对象ID（VIEWING_GROUP=viewing_appointment.id；DIRECT 空）',
    last_message VARCHAR(500) NULL COMMENT '最后一条消息摘要（冗余，列表展示与排序用）',
    last_message_at DATETIME NULL COMMENT '最后一条消息时间（冗余，发消息时同事务维护）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_type_related (type, related_id),
    INDEX idx_community_id (community_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多方会话表（R63）';

CREATE TABLE conversation_participant (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '参与者ID',
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '参与者ID（居民=resident.id，STAFF/ADMIN/SUPER_ADMIN=sys_user.id，多态引用不设外键）',
    last_read_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已读游标（最后已读消息ID，0=全部未读）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入会时间',
    UNIQUE KEY uk_conversation_user (conversation_id, user_id),
    INDEX idx_user_id (user_id),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话参与者表（R63）';

CREATE TABLE conversation_message (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT UNSIGNED NOT NULL COMMENT '会话ID',
    sender_id BIGINT UNSIGNED NOT NULL COMMENT '发送人ID（居民=resident.id，其余=sys_user.id，多态引用不设外键）',
    sender_role VARCHAR(20) NOT NULL COMMENT '发送人角色：RESIDENT/STAFF/ADMIN/SUPER_ADMIN（落库时取认证角色，读侧按角色选表回姓名，消解多态撞号）',
    content VARCHAR(500) NOT NULL COMMENT '消息内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX idx_conversation_id (conversation_id),
    FOREIGN KEY (conversation_id) REFERENCES conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话消息表（R63）';

ALTER TABLE viewing_appointment
    ADD COLUMN conversation_id BIGINT UNSIGNED NULL COMMENT '关联会话ID（R63 看房群聊；游客预约为空）' AFTER assigned_staff_id,
    ADD FOREIGN KEY (conversation_id) REFERENCES conversation(id);

-- -----------------------------------------------------------------------------
-- 存量回填：先建会话并回填 conversation_id，再插参与者（FK 依赖会话已存在）
-- -----------------------------------------------------------------------------

INSERT INTO conversation (type, title, community_id, related_id, last_message, last_message_at, created_at)
SELECT 'VIEWING_GROUP',
       CONCAT('看房沟通·', IFNULL(h.title, '')),
       a.community_id,
       a.id,
       NULL, NULL, NOW()
FROM viewing_appointment a
         LEFT JOIN housing h ON h.id = a.housing_id;

UPDATE viewing_appointment a
    JOIN conversation c ON c.type = 'VIEWING_GROUP' AND c.related_id = a.id
SET a.conversation_id = c.id;

/* 预约居民入群（游客预约 user_id 为空，跳过） */
INSERT IGNORE INTO conversation_participant (conversation_id, user_id, last_read_message_id, created_at)
SELECT DISTINCT c.id, a.user_id, 0, NOW()
FROM viewing_appointment a
         JOIN conversation c ON c.id = a.conversation_id
WHERE a.user_id IS NOT NULL;

/* 社区启用 ADMIN 入群：绑定本社区 + sys_user 启用状态 */
INSERT IGNORE INTO conversation_participant (conversation_id, user_id, last_read_message_id, created_at)
SELECT DISTINCT c.id, u.id, 0, NOW()
FROM sys_admin_community sac
         JOIN sys_user u ON u.id = sac.admin_id
         JOIN conversation c ON c.community_id = sac.community_id AND c.type = 'VIEWING_GROUP'
WHERE u.status = 'ACTIVE' AND u.role = 'ADMIN';

/* 已分配带看人入群（与社区管理员同人时由 INSERT IGNORE 去重） */
INSERT IGNORE INTO conversation_participant (conversation_id, user_id, last_read_message_id, created_at)
SELECT DISTINCT c.id, a.assigned_staff_id, 0, NOW()
FROM viewing_appointment a
         JOIN conversation c ON c.id = a.conversation_id
WHERE a.assigned_staff_id IS NOT NULL;
