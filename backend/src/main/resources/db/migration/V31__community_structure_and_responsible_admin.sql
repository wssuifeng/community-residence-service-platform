-- 2026-10-11 用户批准：移除模糊房间数，责任人只保存ADMIN账号引用。
ALTER TABLE house DROP COLUMN room_count;
ALTER TABLE community
 ADD COLUMN responsible_admin_id BIGINT UNSIGNED NULL COMMENT '社区责任人sys_user账号ID，应用校验ADMIN角色',
 ADD CONSTRAINT fk_community_responsible_admin FOREIGN KEY (responsible_admin_id) REFERENCES sys_user(id),
 DROP COLUMN contact_person,
 DROP COLUMN contact_phone;
