-- V46: Align the persisted notice target description with the current access terminology.
-- V13 is already applied in existing databases, so its Flyway checksum must remain unchanged.
-- This changes column metadata only; no notice rows or visibility rules are modified.
ALTER TABLE notice_target
    MODIFY COLUMN target_type VARCHAR(20) NOT NULL
        COMMENT '目标类型：COMMUNITY-小区, BUILDING-楼栋, GUEST-无需居住关系可见（target_id 固定 0）';
