ALTER TABLE lease_record
 ADD COLUMN contract_months INT NULL,
 ADD COLUMN first_payment_months INT NULL,
 ADD COLUMN payment_cycle_months INT NULL;
CREATE TABLE lease_contract_term (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 lease_id BIGINT UNSIGNED NOT NULL,
 term_type VARCHAR(20) NOT NULL,
 start_date DATE NOT NULL,
 end_date DATE NOT NULL,
 contract_months INT NOT NULL,
 first_payment_months INT NOT NULL,
 payment_cycle_months INT NOT NULL,
 monthly_rent DECIMAL(12,2) NOT NULL,
 deposit DECIMAL(12,2) NOT NULL DEFAULT 0,
 status VARCHAR(30) NOT NULL,
 content_snapshot TEXT NOT NULL,
 confirmed_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 KEY idx_lease_term(lease_id,id),
 CONSTRAINT fk_lease_term_lease FOREIGN KEY(lease_id) REFERENCES lease_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE lease_payment
 ADD COLUMN term_id BIGINT UNSIGNED NULL,
 ADD COLUMN monthly_rent_snapshot DECIMAL(12,2) NULL,
 ADD COLUMN rent_amount DECIMAL(12,2) NULL,
 ADD COLUMN deposit_amount DECIMAL(12,2) NULL,
 ADD COLUMN period_start DATE NULL,
 ADD COLUMN period_end DATE NULL,
 ADD COLUMN channel_expires_at DATETIME NULL,
 ADD COLUMN expires_at DATETIME NULL,
 ADD COLUMN settlement_issue VARCHAR(100) NULL,
 ADD COLUMN channel_success_at DATETIME NULL;

CREATE INDEX idx_payment_term_status ON lease_payment(term_id,status,id);
CREATE INDEX idx_payment_deadline ON lease_payment(status,expires_at);
