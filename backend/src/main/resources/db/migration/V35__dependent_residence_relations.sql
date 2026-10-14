ALTER TABLE residence_relation
 ADD COLUMN anchor_relation_id BIGINT UNSIGNED NULL COMMENT '依赖的主居住关系，不是父账号',
 ADD KEY idx_relation_anchor(anchor_relation_id),
 ADD CONSTRAINT fk_relation_anchor FOREIGN KEY(anchor_relation_id) REFERENCES residence_relation(id) ON DELETE CASCADE,
 ADD COLUMN active_resident_id BIGINT UNSIGNED GENERATED ALWAYS AS (IF(move_out_date IS NULL,resident_id,NULL)) STORED,
 ADD UNIQUE KEY uk_active_house_resident(house_id,active_resident_id);
