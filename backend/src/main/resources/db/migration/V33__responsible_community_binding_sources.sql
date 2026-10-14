ALTER TABLE sys_admin_community
    ADD COLUMN manual_granted TINYINT NOT NULL DEFAULT 1 COMMENT '超管手工授权来源',
    ADD COLUMN responsible_granted TINYINT NOT NULL DEFAULT 0 COMMENT '责任人自动授权来源';

INSERT INTO sys_admin_community(admin_id, community_id, manual_granted, responsible_granted)
SELECT c.responsible_admin_id, c.id, 0, 1
FROM community c JOIN sys_user u ON u.id=c.responsible_admin_id AND u.role='ADMIN'
WHERE c.responsible_admin_id IS NOT NULL
ON DUPLICATE KEY UPDATE responsible_granted=1;
