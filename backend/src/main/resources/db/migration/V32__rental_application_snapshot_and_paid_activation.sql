-- 仅增加溯源与强关联，不以最新挂牌或关系回填历史数据。
ALTER TABLE residence_application
    ADD COLUMN housing_id BIGINT UNSIGNED NULL COMMENT '申请来源挂牌，历史未知为空',
    ADD COLUMN listed_monthly_rent DECIMAL(10,2) NULL COMMENT '申请时挂牌月租快照',
    ADD COLUMN listed_deposit DECIMAL(10,2) NULL COMMENT '申请时挂牌押金快照',
    ADD INDEX idx_application_house_status (house_id, status),
    ADD CONSTRAINT fk_application_source_housing FOREIGN KEY (housing_id) REFERENCES housing(id);
ALTER TABLE residence_relation
    ADD COLUMN lease_id BIGINT UNSIGNED NULL COMMENT '首次成功建立的正式关系强关联；历史未知为空',
    ADD UNIQUE KEY uk_relation_lease (lease_id),
    ADD INDEX idx_relation_house_moveout (house_id, move_out_date);
-- lease_id为域内强关联；社区删除先删租约后删关系的既有流程保持兼容。
