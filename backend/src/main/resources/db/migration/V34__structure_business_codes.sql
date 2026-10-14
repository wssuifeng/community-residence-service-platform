ALTER TABLE community ADD COLUMN business_code VARCHAR(32) NULL COMMENT '社区业务编号';
ALTER TABLE building ADD COLUMN business_code VARCHAR(32) NULL COMMENT '社区内楼栋业务编号';
ALTER TABLE unit ADD COLUMN business_code VARCHAR(32) NULL COMMENT '楼栋内单元业务编号';
UPDATE community c JOIN (SELECT id,CAST(ROW_NUMBER() OVER(ORDER BY id) AS CHAR) code FROM community) x ON x.id=c.id SET c.business_code=x.code;
UPDATE building b JOIN (
 SELECT id,CASE WHEN candidate IS NOT NULL AND COUNT(*) OVER(PARTITION BY community_id,candidate)=1 THEN candidate ELSE CONCAT('B',ROW_NUMBER() OVER(PARTITION BY community_id ORDER BY id)) END code
 FROM (SELECT id,community_id,CASE WHEN REGEXP_LIKE(name,'^[0-9]+(号楼|栋|幢)?$') THEN REGEXP_SUBSTR(name,'^[0-9]+') END candidate FROM building) t
) x ON x.id=b.id SET b.business_code=x.code;
UPDATE unit u JOIN (
 SELECT id,CASE WHEN candidate IS NOT NULL AND COUNT(*) OVER(PARTITION BY building_id,candidate)=1 THEN candidate ELSE CONCAT('U',ROW_NUMBER() OVER(PARTITION BY building_id ORDER BY id)) END code
 FROM (SELECT id,building_id,CASE WHEN REGEXP_LIKE(name,'^[0-9]+(单元)?$') THEN REGEXP_SUBSTR(name,'^[0-9]+') END candidate FROM unit) t
) x ON x.id=u.id SET u.business_code=x.code;
ALTER TABLE community ADD UNIQUE KEY uk_community_business_code(business_code);
ALTER TABLE building ADD UNIQUE KEY uk_building_business_code(community_id,business_code);
ALTER TABLE unit ADD UNIQUE KEY uk_unit_business_code(building_id,business_code);
INSERT INTO sys_config(config_key,config_value,description) VALUES('structure.code.sequence','0','结构业务编号分配串行锁') ON DUPLICATE KEY UPDATE config_key=config_key;
