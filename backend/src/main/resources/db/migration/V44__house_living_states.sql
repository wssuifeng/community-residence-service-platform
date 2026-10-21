-- R4 actual house state; temporary reservation remains application/lease data, not a living state.
CREATE TEMPORARY TABLE house_state_v44 AS
SELECT h.id, h.community_id, h.status old_status,
 CASE WHEN EXISTS (SELECT 1 FROM residence_relation r WHERE r.house_id=h.id AND r.move_out_date IS NULL AND r.relation_type='TENANT' AND r.move_in_date<=CURRENT_DATE()) THEN 'TENANT_OCCUPIED'
      WHEN EXISTS (SELECT 1 FROM residence_relation r WHERE r.house_id=h.id AND r.move_out_date IS NULL AND r.relation_type='OWNER' AND r.move_in_date<=CURRENT_DATE()) THEN 'OWNER_OCCUPIED'
      WHEN h.status IN ('MAINTENANCE','INACTIVE') THEN h.status ELSE 'VACANT' END new_status
FROM house h;
INSERT INTO house_status_history(house_id,community_id,old_status,new_status,operator_id,remark)
 SELECT id,community_id,old_status,new_status,0,'V44: derive actual state from effective main residence; temporary occupancy stays separate'
 FROM house_state_v44 WHERE old_status<>new_status;
UPDATE house h JOIN house_state_v44 s ON s.id=h.id SET h.status=s.new_status WHERE s.old_status<>s.new_status;
DROP TEMPORARY TABLE house_state_v44;
ALTER TABLE house MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'VACANT' COMMENT 'VACANT,OWNER_OCCUPIED,TENANT_OCCUPIED,MAINTENANCE,INACTIVE';
