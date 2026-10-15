-- User 2026-10-15: real image messages, editable notice pictures and persisted anonymity.
ALTER TABLE notice ADD COLUMN images VARCHAR(1000) NULL COMMENT 'Platform uploaded picture URLs';
ALTER TABLE feedback ADD COLUMN is_anonymous TINYINT NULL COMMENT 'NULL: historical choice was not persisted; 0: named; 1: anonymous';
ALTER TABLE feedback_message ADD COLUMN message_type VARCHAR(30) NOT NULL DEFAULT 'TEXT';
ALTER TABLE viewing_message ADD COLUMN message_type VARCHAR(30) NOT NULL DEFAULT 'TEXT';
CREATE TABLE chat_message_image (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 community_id BIGINT UNSIGNED NOT NULL,
 message_scope VARCHAR(20) NOT NULL,
 message_id BIGINT UNSIGNED NOT NULL,
 file_url VARCHAR(500) NOT NULL,
 file_name VARCHAR(255) NOT NULL,
 file_size BIGINT NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_chat_scope_message(message_scope,message_id),
 KEY idx_chat_image_community(community_id),
 CONSTRAINT fk_chat_image_community FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
