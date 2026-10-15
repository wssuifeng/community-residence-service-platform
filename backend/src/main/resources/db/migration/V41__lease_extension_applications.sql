CREATE TABLE lease_extension_application (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 lease_id BIGINT UNSIGNED NOT NULL, community_id BIGINT UNSIGNED NOT NULL, resident_id BIGINT UNSIGNED NOT NULL,
 months INT NOT NULL, original_end_date DATE NOT NULL, proposed_end_date DATE NOT NULL,
 monthly_rent DECIMAL(12,2) NOT NULL, deposit DECIMAL(12,2) NULL,
 reason VARCHAR(500) NULL, status VARCHAR(20) NOT NULL,
 review_by BIGINT UNSIGNED NULL, reviewed_at DATETIME NULL, review_note VARCHAR(500) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 pending_guard TINYINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_extension_pending(lease_id,pending_guard), KEY idx_extension_community_status(community_id,status),
 CONSTRAINT fk_extension_lease FOREIGN KEY(lease_id) REFERENCES lease_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE lease_payment ADD COLUMN coverage_details JSON NULL;
