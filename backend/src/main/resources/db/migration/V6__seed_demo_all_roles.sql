-- =============================================================================
-- Flyway Migration V6: Seed Demo Data for All Five Roles
-- =============================================================================
-- Description: 五角色演示种子数据（系统定义 C10 五角色：游客/居民/服务人员/
--              社区管理员/超级管理员）。让新环境开箱即可体验全部端：
--              - 游客（无账号）：浏览公告/房源、预约看房 → 由公开数据支撑
--              - 居民 resident1：已入住，可提交工单/反馈/资源预约
--              - 服务人员 staff1：接派单处理工单
--              - 社区管理员 admin1：绑定社区 1，社区范围后台管理
--              - 超级管理员 superadmin：V3 已建，不重复
-- Accounts & Passwords:
--   admin1    / Admin123456    （ADMIN，绑定社区 1）
--   staff1    / Staff123456    （STAFF，数据权限走派单关系，不建社区绑定）
--   resident1 / Resident123456 （RESIDENT，已入住社区 1 房屋 101）
--   游客无账号（走公开接口），超管见 V3
-- Note: 脚本幂等（INSERT ... SELECT ... WHERE NOT EXISTS / 唯一键防重），
--       但 Flyway 正常流程只执行一次；密码 BCrypt 强度 10，生产环境须改
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 社区基础结构：社区 → 楼栋 → 单元 → 房屋（社区 1）
-- -----------------------------------------------------------------------------
INSERT INTO community (name, address, contact_phone, contact_person, status)
SELECT '阳光花园社区', '示范市幸福路 88 号', '0571-88880001', '物业服务中心', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM community WHERE name = '阳光花园社区');

SET @seed_community_id = (SELECT id FROM community WHERE name = '阳光花园社区' LIMIT 1);

INSERT INTO building (community_id, name, floors, description)
SELECT @seed_community_id, '1 号楼', 18, 'Seed 演示楼栋'
WHERE NOT EXISTS (SELECT 1 FROM building WHERE community_id = @seed_community_id AND name = '1 号楼');

SET @seed_building_id = (SELECT id FROM building WHERE community_id = @seed_community_id AND name = '1 号楼' LIMIT 1);

INSERT INTO unit (building_id, community_id, name)
SELECT @seed_building_id, @seed_community_id, '1 单元'
WHERE NOT EXISTS (SELECT 1 FROM unit WHERE building_id = @seed_building_id AND name = '1 单元');

SET @seed_unit_id = (SELECT id FROM unit WHERE building_id = @seed_building_id AND name = '1 单元' LIMIT 1);

-- 房屋三套：101（居民已入住）、102（空置 + 在架房源供游客浏览）、201（空置备用）
INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status)
SELECT @seed_unit_id, @seed_community_id, '101', 1, 89.50, 2, '2室1厅1卫', 'SOUTH', 'OCCUPIED'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @seed_unit_id AND house_number = '101');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status)
SELECT @seed_unit_id, @seed_community_id, '102', 1, 120.00, 3, '3室2厅2卫', 'SOUTH', 'VACANT'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @seed_unit_id AND house_number = '102');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status)
SELECT @seed_unit_id, @seed_community_id, '201', 2, 89.50, 2, '2室1厅1卫', 'NORTH', 'VACANT'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @seed_unit_id AND house_number = '201');

-- -----------------------------------------------------------------------------
-- 2. 后台账号：社区管理员 + 服务人员（sys_user；游客无账号、超管见 V3）
-- -----------------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'admin1', '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK',
       '社区管理员', '13800000101', 'ADMIN', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin1');

INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'staff1', '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu',
       '服务人员', '13800000102', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'staff1');

SET @seed_admin_id = (SELECT id FROM sys_user WHERE username = 'admin1' LIMIT 1);

-- 管理员绑定社区 1（ADMIN 数据级权限权威来源；STAFF 不绑定，走派单关系）
INSERT INTO sys_admin_community (admin_id, community_id)
SELECT @seed_admin_id, @seed_community_id
WHERE NOT EXISTS (
    SELECT 1 FROM sys_admin_community
    WHERE admin_id = @seed_admin_id AND community_id = @seed_community_id
);

-- -----------------------------------------------------------------------------
-- 3. 居民账号 + 入住链路（居住关系 + 租约；对应入住审批通过的终态数据）
-- -----------------------------------------------------------------------------
INSERT INTO resident (username, password_hash, real_name, phone, status)
SELECT 'resident1', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC',
       '演示居民', '13800000103', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM resident WHERE username = 'resident1');

SET @seed_resident_id = (SELECT id FROM resident WHERE username = 'resident1' LIMIT 1);
SET @seed_house_101 = (SELECT id FROM house WHERE unit_id = @seed_unit_id AND house_number = '101' LIMIT 1);
SET @seed_house_102 = (SELECT id FROM house WHERE unit_id = @seed_unit_id AND house_number = '102' LIMIT 1);

-- 居住关系（业主入住 101）
INSERT INTO residence_relation (resident_id, community_id, house_id, relation_type, move_in_date, is_primary)
SELECT @seed_resident_id, @seed_community_id, @seed_house_101, 'OWNER', '2026-01-01', 1
WHERE NOT EXISTS (
    SELECT 1 FROM residence_relation
    WHERE resident_id = @seed_resident_id AND house_id = @seed_house_101
);

-- 租约（已生效）
INSERT INTO lease_record (tenant_id, community_id, house_id, start_date, end_date, monthly_rent, deposit, status)
SELECT @seed_resident_id, @seed_community_id, @seed_house_101, '2026-01-01', '2027-12-31', 3000.00, 6000.00, 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM lease_record WHERE tenant_id = @seed_resident_id AND house_id = @seed_house_101
);

-- -----------------------------------------------------------------------------
-- 4. 公开数据（游客可见）：公告 + 在架房源 + 看房时段模板
-- -----------------------------------------------------------------------------
-- 已发布公告（发布人 = admin1；定向社区 1）
INSERT INTO notice (title, content, publisher_id, status, publish_time, start_time, end_time, view_count)
SELECT '欢迎入住阳光花园社区', '欢迎使用社区居住服务管理系统。物业服务热线 0571-88880001，公共资源预约、报修工单均可在线办理。',
       @seed_admin_id, 'PUBLISHED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 365 DAY), 0
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '欢迎入住阳光花园社区');

SET @seed_notice_id = (SELECT id FROM notice WHERE title = '欢迎入住阳光花园社区' LIMIT 1);

INSERT INTO notice_target (notice_id, target_type, target_id)
SELECT @seed_notice_id, 'COMMUNITY', @seed_community_id
WHERE NOT EXISTS (
    SELECT 1 FROM notice_target
    WHERE notice_id = @seed_notice_id AND target_type = 'COMMUNITY' AND target_id = @seed_community_id
);

-- 在架房源（关联空置房屋 102，游客/居民可浏览并预约看房）
INSERT INTO housing (community_id, house_id, title, description, monthly_rent, deposit, status, view_count, publish_time)
SELECT @seed_community_id, @seed_house_102, '阳光花园 1 号楼 102 室 · 三室两厅',
       'Seed 演示房源：南向三居，采光充足，近地铁，拎包入住。', 4500.00, 9000.00, 'AVAILABLE', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM housing WHERE house_id = @seed_house_102);

SET @seed_housing_id = (SELECT id FROM housing WHERE house_id = @seed_house_102 LIMIT 1);

-- 看房时段模板（周一至周日 上午 + 下午，周循环）
INSERT INTO housing_timeslot (housing_id, day_of_week, start_time, end_time, is_available)
SELECT @seed_housing_id, d.dow, t.start_time, t.end_time, 1
FROM (SELECT 1 AS dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7) d
CROSS JOIN (SELECT '09:00:00' AS start_time, '12:00:00' AS end_time
            UNION SELECT '14:00:00', '18:00:00') t
WHERE NOT EXISTS (SELECT 1 FROM housing_timeslot WHERE housing_id = @seed_housing_id);

-- -----------------------------------------------------------------------------
-- 5. 公共资源 + 时段（居民端资源预约演示）
-- -----------------------------------------------------------------------------
-- 公共资源表无 status 列（可用性由软删除标记表达）
INSERT INTO public_resource (community_id, name, type, location, capacity, description)
SELECT @seed_community_id, '健身房', 'GYM', '1 号楼首层', 10, 'Seed 演示资源：跑步机、椭圆机、力量区'
WHERE NOT EXISTS (SELECT 1 FROM public_resource WHERE community_id = @seed_community_id AND name = '健身房');

SET @seed_resource_id = (SELECT id FROM public_resource WHERE community_id = @seed_community_id AND name = '健身房' LIMIT 1);

-- 资源时段模板（周一至周五 上午 + 下午）
INSERT INTO resource_timeslot (resource_id, community_id, day_of_week, start_time, end_time, is_available)
SELECT @seed_resource_id, @seed_community_id, d.dow, t.start_time, t.end_time, 1
FROM (SELECT 1 AS dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) d
CROSS JOIN (SELECT '09:00:00' AS start_time, '12:00:00' AS end_time
            UNION SELECT '14:00:00', '18:00:00') t
WHERE NOT EXISTS (SELECT 1 FROM resource_timeslot WHERE resource_id = @seed_resource_id);

-- -----------------------------------------------------------------------------
-- 脚本结束
-- -----------------------------------------------------------------------------
