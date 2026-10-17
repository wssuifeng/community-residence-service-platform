CREATE TABLE feedback_read_cursor (
 feedback_id BIGINT UNSIGNED NOT NULL COMMENT '所属反馈',
 owner_type VARCHAR(16) NOT NULL COMMENT '身份空间RESIDENT/USER',
 user_id BIGINT UNSIGNED NOT NULL COMMENT '身份空间内账号ID',
 last_read_message_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最后可见消息游标',
 initial_read TINYINT NOT NULL DEFAULT 0 COMMENT '原始反馈已展示',
 PRIMARY KEY(feedback_id,owner_type,user_id),
 CONSTRAINT fk_feedback_read_cursor_feedback FOREIGN KEY(feedback_id) REFERENCES feedback(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈独立聊天已读游标';
