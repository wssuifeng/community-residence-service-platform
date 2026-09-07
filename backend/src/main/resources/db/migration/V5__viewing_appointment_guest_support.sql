-- V5: 看房预约支持游客预约（接口设计.md 9.12.2.1 GUEST 可预约，数据库设计 user_id NOT NULL 与之矛盾，
-- 以接口契约为准放开：居民预约带 user_id，游客预约带 visitor_name/visitor_phone，user_id 可空）
-- 创建时间：2026-09-07，见决策日志同日条目

ALTER TABLE viewing_appointment
DROP FOREIGN KEY viewing_appointment_ibfk_1;

ALTER TABLE viewing_appointment
MODIFY COLUMN user_id BIGINT UNSIGNED NULL COMMENT '预约人ID（游客预约为空）',
ADD COLUMN visitor_name VARCHAR(50) NULL COMMENT '游客姓名（游客预约时填写）' AFTER user_id,
ADD COLUMN visitor_phone VARCHAR(20) NULL COMMENT '游客手机号（游客预约时填写）' AFTER visitor_name;
