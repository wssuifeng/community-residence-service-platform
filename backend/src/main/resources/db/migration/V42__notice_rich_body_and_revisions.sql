ALTER TABLE notice ADD COLUMN body_json JSON NULL,
 ADD COLUMN content_version INT NOT NULL DEFAULT 1,
 ADD COLUMN edited_by BIGINT UNSIGNED NULL, ADD COLUMN edited_at DATETIME NULL;
CREATE TABLE notice_revision (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY, notice_id BIGINT UNSIGNED NOT NULL,
 content_version INT NOT NULL, title VARCHAR(200) NOT NULL, content TEXT NOT NULL, body_json JSON NULL, images TEXT NULL,
 editor_id BIGINT UNSIGNED NULL, edited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_notice_revision(notice_id,content_version),
 CONSTRAINT fk_notice_revision_notice FOREIGN KEY(notice_id) REFERENCES notice(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE notice_view_record ADD COLUMN content_version INT NOT NULL DEFAULT 1;
CREATE TABLE notice_version_view (
 notice_id BIGINT UNSIGNED NOT NULL, user_id BIGINT UNSIGNED NOT NULL, content_version INT NOT NULL,
 view_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(notice_id,user_id,content_version),
 CONSTRAINT fk_notice_version_view_notice FOREIGN KEY(notice_id) REFERENCES notice(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO notice_version_view(notice_id,user_id,content_version,view_time)
 SELECT notice_id,user_id,1,view_time FROM notice_view_record;
