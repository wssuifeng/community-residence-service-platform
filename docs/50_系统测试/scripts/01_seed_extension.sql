-- =============================================================================
-- 01_seed_extension.sql · 50 系统测试 · 步骤 5 · 测试专用种子扩展
-- =============================================================================
-- 前提：00_create_test_db.sql 已建库，且后端已对测试库完成 Flyway V1~V8
--       （含超管 superadmin / V6 五角色演示种子 / V7 服务类别 / V8 日志留存配置）。
-- 执行（先切到测试库！）：
--   mysql -u root -p community_residence_test < 01_seed_extension.sql
-- 内容（在 V6/V7 种子基础上补充测试专用数据）：
--   1. 测试专用社区 TEST-清源里社区：完整四级结构（社区→楼栋→单元→房屋 6 套，
--      房屋状态覆盖 VACANT/OCCUPIED/RESERVED/MAINTENANCE）+ 2 个公共资源 + 全周时段；
--   2. 多角色测试账号（sys_user / resident）：
--      test_admin（ADMIN，绑定清源里社区）/ test_admin2（ADMIN，绑定 V6 阳光花园
--      社区，越权对照）/ test_staff（STAFF）/ test_staff_disabled（STAFF，冻结，
--      派单拒绝用）/ test_resident（入住清源里）/ test_resident2（入住阳光花园，
--      数据级隔离对照）/ test_resident_frozen（冻结，拒登用）；
--   3. N2 压测 200 账号池：perf_resident_001 ~ perf_resident_200（居民端点登录，
--      最小必要字段，密码统一 Resident123456）；
--   4. 冲突并发用资源与时段（TR-SP-01 / R33 / R55）：
--      并发测试健身房（10 档 08:00~18:00 时段，每档 1 小时，全周开放）+ 看房房源；
--   5. 看房测试房源与时段（R54 / R55 / E12：全周 09:00~18:00 每小时一档）；
--   6. 通知/公告测试数据锚点（E11 / TR-C11：已发布公告定向清源里社区 +
--      预置通知锚点若干，供补拉/已读/渠道留痕用例断言）。
-- 密码哈希来源（BCrypt，直接复用 V6/V3 已验证的哈希，同一明文）：
--   Admin123456     → '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK'（V6 admin1）
--   Staff123456     → '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu'（V6 staff1）
--   Resident123456  → '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC'（V6 resident1）
--   Admin@123456    → '$2a$10$N3Jnj4sTBr.GclN0CIX37.6fHWnYs1Hf.1OcKWxSECPm80xwsCKla'（V3 superadmin）
-- 幂等性：全部 INSERT ... SELECT ... WHERE NOT EXISTS（参照 V6 写法），可重复执行。
-- 注意：notification.seq 生产口径为 Redis INCR 全局递增；本脚本预置通知的 seq
--       取「当前最大序号 + 行号」且先同步 Redis 计数器，保证后启动后新通知
--       seq 仍全局递增（Redis 键 notification:seq，见 NotificationService）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Redis 计数器同步提示（人工步骤，SQL 无法代做）
-- -----------------------------------------------------------------------------
-- 若后端尚未启动过（Redis 中 notification:seq 不存在），本脚本预置通知的 seq
-- 从 1 起排布，无需处理；若后端已在测试库运行过（seq 已是较大值），重跑本脚本
-- 时预置通知幂等跳过，不会产生 seq 冲突。仅当「后端已运行 + 需要新增预置通知」
-- 时，才需要在 Redis 手动对齐：SET notification:seq <当前库内 MAX(seq)>。

-- -----------------------------------------------------------------------------
-- 1. 测试专用社区 TEST-清源里（完整四级结构 + 房屋状态全覆盖）
-- -----------------------------------------------------------------------------
INSERT INTO community (name, address, contact_phone, contact_person, description, status)
SELECT 'TEST-清源里社区', '测试市静心路 66 号', '0571-88000066', '测试物业中心',
       '50 阶段测试专用社区（步骤 5 种子扩展）', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM community WHERE name = 'TEST-清源里社区');

SET @test_community_id = (SELECT id FROM community WHERE name = 'TEST-清源里社区' LIMIT 1);

INSERT INTO building (community_id, name, floors, description)
SELECT @test_community_id, 'T1 号楼', 6, '测试楼栋 1'
WHERE NOT EXISTS (SELECT 1 FROM building WHERE community_id = @test_community_id AND name = 'T1 号楼');

INSERT INTO building (community_id, name, floors, description)
SELECT @test_community_id, 'T2 号楼', 6, '测试楼栋 2（删除保护用，无下级单元）'
WHERE NOT EXISTS (SELECT 1 FROM building WHERE community_id = @test_community_id AND name = 'T2 号楼');

SET @test_building_id = (SELECT id FROM building WHERE community_id = @test_community_id AND name = 'T1 号楼' LIMIT 1);
SET @test_building2_id = (SELECT id FROM building WHERE community_id = @test_community_id AND name = 'T2 号楼' LIMIT 1);

INSERT INTO unit (building_id, community_id, name)
SELECT @test_building_id, @test_community_id, 'T1-1 单元'
WHERE NOT EXISTS (SELECT 1 FROM unit WHERE building_id = @test_building_id AND name = 'T1-1 单元');

INSERT INTO unit (building_id, community_id, name)
SELECT @test_building_id, @test_community_id, 'T1-2 单元'
WHERE NOT EXISTS (SELECT 1 FROM unit WHERE building_id = @test_building_id AND name = 'T1-2 单元');

SET @test_unit_id = (SELECT id FROM unit WHERE building_id = @test_building_id AND name = 'T1-1 单元' LIMIT 1);
SET @test_unit2_id = (SELECT id FROM unit WHERE building_id = @test_building_id AND name = 'T1-2 单元' LIMIT 1);

-- 6 套房屋：状态覆盖 VACANT / OCCUPIED / RESERVED / MAINTENATION（五状态中
-- RESERVED/MAINTENANCE 为 V1 注释口径；停用语义由 MAINTENANCE+描述表达，
-- 用例设计若需独立停用态以 04 用例文件口径为准）
INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit_id, @test_community_id, '101', 1, 79.00, 2, '2室1厅1卫', 'SOUTH', 'OCCUPIED', '测试房屋：test_resident 入住'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit_id AND house_number = '101');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit_id, @test_community_id, '102', 1, 118.00, 3, '3室2厅2卫', 'SOUTH', 'VACANT', '测试房屋：空置 + 在架房源（看房预约链路）'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit_id AND house_number = '102');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit_id, @test_community_id, '201', 2, 79.00, 2, '2室1厅1卫', 'NORTH', 'RESERVED', '测试房屋：预留态'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit_id AND house_number = '201');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit_id, @test_community_id, '301', 3, 79.00, 2, '2室1厅1卫', 'SOUTH', 'MAINTENANCE', '测试房屋：维护中'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit_id AND house_number = '301');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit2_id, @test_community_id, '101', 1, 95.00, 3, '3室1厅1卫', 'EAST', 'VACANT', '测试房屋：空置（删除保护用，无业务引用）'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit2_id AND house_number = '101');

INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
SELECT @test_unit2_id, @test_community_id, '201', 2, 95.00, 3, '3室1厅1卫', 'WEST', 'VACANT', '测试房屋：空置（N7 批量外的对照房）'
WHERE NOT EXISTS (SELECT 1 FROM house WHERE unit_id = @test_unit2_id AND house_number = '201');

SET @test_house_101 = (SELECT id FROM house WHERE unit_id = @test_unit_id AND house_number = '101' LIMIT 1);
SET @test_house_102 = (SELECT id FROM house WHERE unit_id = @test_unit_id AND house_number = '102' LIMIT 1);
SET @test_unit2_house_101 = (SELECT id FROM house WHERE unit_id = @test_unit2_id AND house_number = '101' LIMIT 1);

-- -----------------------------------------------------------------------------
-- 2. 多角色测试账号（sys_user：管理员/服务人员；resident：居民）
--    密码哈希复用 V6（明文见文件头注释），手机号避开 V6 已用段 138000001xx
-- -----------------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'test_admin', '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK',
       '测试管理员', '13800001001', 'ADMIN', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'test_admin');

INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'test_admin2', '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK',
       '测试管理员（阳光花园，越权对照）', '13800001002', 'ADMIN', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'test_admin2');

INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'test_staff', '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu',
       '测试服务人员', '13800001003', 'STAFF', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'test_staff');

INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
SELECT 'test_staff_disabled', '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu',
       '测试服务人员（冻结，派单拒绝用）', '13800001004', 'STAFF', 'FROZEN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'test_staff_disabled');

SET @test_admin_id  = (SELECT id FROM sys_user WHERE username = 'test_admin' LIMIT 1);
SET @test_admin2_id = (SELECT id FROM sys_user WHERE username = 'test_admin2' LIMIT 1);
SET @seed_community_sunny = (SELECT id FROM community WHERE name = '阳光花园社区' LIMIT 1);

-- 绑定：test_admin → 清源里；test_admin2 → 阳光花园（V6 社区，越权对照）
INSERT INTO sys_admin_community (admin_id, community_id)
SELECT @test_admin_id, @test_community_id
WHERE NOT EXISTS (
    SELECT 1 FROM sys_admin_community
    WHERE admin_id = @test_admin_id AND community_id = @test_community_id
);

INSERT INTO sys_admin_community (admin_id, community_id)
SELECT @test_admin2_id, @seed_community_sunny
WHERE NOT EXISTS (
    SELECT 1 FROM sys_admin_community
    WHERE admin_id = @test_admin2_id AND community_id = @seed_community_sunny
);

-- 居民账号（密码统一 Resident123456，哈希同 V6 resident1）
INSERT INTO resident (username, password_hash, real_name, phone, status)
SELECT 'test_resident', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC',
       '测试居民', '13800001005', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM resident WHERE username = 'test_resident');

INSERT INTO resident (username, password_hash, real_name, phone, status)
SELECT 'test_resident2', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC',
       '测试居民（阳光花园，隔离对照）', '13800001006', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM resident WHERE username = 'test_resident2');

INSERT INTO resident (username, password_hash, real_name, phone, status)
SELECT 'test_resident_frozen', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC',
       '测试居民（冻结，拒登用）', '13800001007', 'FROZEN'
WHERE NOT EXISTS (SELECT 1 FROM resident WHERE username = 'test_resident_frozen');

SET @test_resident_id  = (SELECT id FROM resident WHERE username = 'test_resident' LIMIT 1);
SET @test_resident2_id = (SELECT id FROM resident WHERE username = 'test_resident2' LIMIT 1);
SET @seed_resident_sunny = (SELECT id FROM resident WHERE username = 'resident1' LIMIT 1);

-- 居住关系：test_resident 入住清源里 101（OWNER，当前有效）；
-- test_resident2 入住阳光花园 101（FAMILY，与 V6 resident1 同房共住——
-- 居住关系为 M:N，多居民共住一房合法，同时充当跨社区数据隔离对照）。
INSERT INTO residence_relation (resident_id, community_id, house_id, relation_type, move_in_date, is_primary)
SELECT @test_resident_id, @test_community_id, @test_house_101, 'OWNER', DATE_SUB(CURDATE(), INTERVAL 200 DAY), 1
WHERE NOT EXISTS (
    SELECT 1 FROM residence_relation
    WHERE resident_id = @test_resident_id AND house_id = @test_house_101
);

SET @sunny_house_101 = (SELECT h.id FROM house h
                        JOIN unit u ON h.unit_id = u.id
                        JOIN building b ON u.building_id = b.id
                        WHERE b.community_id = @seed_community_sunny AND h.house_number = '101' LIMIT 1);

INSERT INTO residence_relation (resident_id, community_id, house_id, relation_type, move_in_date, is_primary)
SELECT @test_resident2_id, @seed_community_sunny, @sunny_house_101, 'FAMILY', DATE_SUB(CURDATE(), INTERVAL 100 DAY), 0
WHERE NOT EXISTS (
    SELECT 1 FROM residence_relation
    WHERE resident_id = @test_resident2_id AND house_id = @sunny_house_101
);

-- -----------------------------------------------------------------------------
-- 3. N2 压测 200 账号池（perf_resident_001 ~ 200）
--    居民端点登录走最小必要字段（username/password_hash/real_name/phone/status），
--    手机号占用 13810000001~13810000200 段，密码统一 Resident123456。
--    注：压测账号不建居住关系（登录与列表接口不依赖）；如压测用例需要
--    「我的工单」等需入住的接口，执行 02_bulk_data.sql 时会为其补建关系。
-- -----------------------------------------------------------------------------
-- 生成器：百位 h(0/1) × 十位 t(0~9) × 个位 u(0~9) 组合出 1~200，无重复行
INSERT INTO resident (username, password_hash, real_name, phone, status)
SELECT CONCAT('perf_resident_', LPAD(h.hh * 100 + t.tt * 10 + u.uu + 1, 3, '0')),
       '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC',
       CONCAT('压测居民', LPAD(h.hh * 100 + t.tt * 10 + u.uu + 1, 3, '0')),
       CONCAT('1381000', LPAD(h.hh * 100 + t.tt * 10 + u.uu + 1, 4, '0')),
       'ACTIVE'
FROM (SELECT 0 AS hh UNION ALL SELECT 1) h
CROSS JOIN (
    SELECT 0 AS tt UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) t
CROSS JOIN (
    SELECT 0 AS uu UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
) u
WHERE h.hh * 100 + t.tt * 10 + u.uu + 1 BETWEEN 1 AND 200
  AND NOT EXISTS (
      SELECT 1 FROM resident
      WHERE username = CONCAT('perf_resident_', LPAD(h.hh * 100 + t.tt * 10 + u.uu + 1, 3, '0'))
  );

-- -----------------------------------------------------------------------------
-- 4. 冲突并发用资源与时段（TR-SP-01 / R33）
--    并发测试健身房：全周开放，每天 08:00~18:00 共 10 档整点时段，
--    每档 1 小时——并发用例按档位整数边界构造「半重叠 / 首尾相接」请求对。
-- -----------------------------------------------------------------------------
INSERT INTO public_resource (community_id, name, type, location, capacity, description)
SELECT @test_community_id, 'TEST-并发测试健身房', 'GYM', 'T1 号楼首层', 1,
       'SP-01 冲突并发专用资源（容量 1，同一时段仅一路预约可成功）'
WHERE NOT EXISTS (SELECT 1 FROM public_resource WHERE community_id = @test_community_id AND name = 'TEST-并发测试健身房');

SET @test_conflict_resource_id = (SELECT id FROM public_resource WHERE community_id = @test_community_id AND name = 'TEST-并发测试健身房' LIMIT 1);

INSERT INTO resource_timeslot (resource_id, community_id, day_of_week, start_time, end_time, is_available)
SELECT @test_conflict_resource_id, @test_community_id, d.dow, s.slot_start,
       SEC_TO_TIME(TIME_TO_SEC(s.slot_start) + 3600), 1
FROM (SELECT 1 AS dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7) d
CROSS JOIN (
    SELECT '08:00:00' AS slot_start UNION SELECT '09:00:00' UNION SELECT '10:00:00'
    UNION SELECT '11:00:00' UNION SELECT '12:00:00' UNION SELECT '13:00:00'
    UNION SELECT '14:00:00' UNION SELECT '15:00:00' UNION SELECT '16:00:00' UNION SELECT '17:00:00'
) s
WHERE NOT EXISTS (SELECT 1 FROM resource_timeslot WHERE resource_id = @test_conflict_resource_id);

-- -----------------------------------------------------------------------------
-- 5. 看房测试房源与时段（R54 / R55 / E12）
--    房源关联清源里空置房 102；全周 09:00~18:00 每小时一档（9 档/天），
--    时段外请求（如 08:00 或 18:30）应被拒——TR-C12-03 时段外口径。
-- -----------------------------------------------------------------------------
INSERT INTO housing (community_id, house_id, title, description, monthly_rent, deposit, status, view_count, publish_time)
SELECT @test_community_id, @test_house_102, 'TEST-清源里 T1-101·两室南向·看房预约测试房源',
       '50 阶段测试专用房源：全周 09:00~18:00 可约，SP-01 看房冲突并发同用。',
       3800.00, 7600.00, 'AVAILABLE', 0, NOW()
WHERE NOT EXISTS (SELECT 1 FROM housing WHERE community_id = @test_community_id AND house_id = @test_house_102);

SET @test_housing_id = (SELECT id FROM housing WHERE community_id = @test_community_id AND house_id = @test_house_102 LIMIT 1);

INSERT INTO housing_timeslot (housing_id, day_of_week, start_time, end_time, is_available)
SELECT @test_housing_id, d.dow, s.slot_start,
       SEC_TO_TIME(TIME_TO_SEC(s.slot_start) + 3600), 1
FROM (SELECT 1 AS dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7) d
CROSS JOIN (
    SELECT '09:00:00' AS slot_start UNION SELECT '10:00:00' UNION SELECT '11:00:00'
    UNION SELECT '12:00:00' UNION SELECT '13:00:00' UNION SELECT '14:00:00'
    UNION SELECT '15:00:00' UNION SELECT '16:00:00' UNION SELECT '17:00:00'
) s
WHERE NOT EXISTS (SELECT 1 FROM housing_timeslot WHERE housing_id = @test_housing_id);

-- -----------------------------------------------------------------------------
-- 6. 通知/公告测试数据锚点（E11 / TR-C11）
-- -----------------------------------------------------------------------------
-- 6.1 已发布公告（test_admin 发布，定向清源里社区；有效期覆盖整个测试窗口）
INSERT INTO notice (title, content, publisher_id, status, publish_time, start_time, end_time, view_count)
SELECT 'TEST-清源里社区业主大会通知', '50 阶段测试锚点公告：本周六上午 9 时于 T1 号楼首层会议室召开业主大会，请各位业主准时出席。',
       @test_admin_id, 'PUBLISHED', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 365 DAY), 0
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = 'TEST-清源里社区业主大会通知');

SET @test_notice_id = (SELECT id FROM notice WHERE title = 'TEST-清源里社区业主大会通知' LIMIT 1);

INSERT INTO notice_target (notice_id, target_type, target_id)
SELECT @test_notice_id, 'COMMUNITY', @test_community_id
WHERE NOT EXISTS (
    SELECT 1 FROM notice_target
    WHERE notice_id = @test_notice_id AND target_type = 'COMMUNITY' AND target_id = @test_community_id
);

-- 6.2 过期公告（end_time 已过，仍 PUBLISHED——验证定时任务口径下线 +
--     查询期过滤兜底，TR-C5-04 / SP-03）
INSERT INTO notice (title, content, publisher_id, status, publish_time, start_time, end_time, view_count)
SELECT 'TEST-已过期公告（下线验证锚点）', '50 阶段测试锚点：本公告失效时间已过，居民端列表不应展示。',
       @test_admin_id, 'PUBLISHED', DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 40 DAY),
       DATE_SUB(NOW(), INTERVAL 10 DAY), 0
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = 'TEST-已过期公告（下线验证锚点）');

SET @test_notice_expired_id = (SELECT id FROM notice WHERE title = 'TEST-已过期公告（下线验证锚点）' LIMIT 1);

INSERT INTO notice_target (notice_id, target_type, target_id)
SELECT @test_notice_expired_id, 'COMMUNITY', @test_community_id
WHERE NOT EXISTS (
    SELECT 1 FROM notice_target
    WHERE notice_id = @test_notice_expired_id AND target_type = 'COMMUNITY' AND target_id = @test_community_id
);

-- 6.3 预置通知锚点（test_resident 收 4 条：未读 3 + 已读 1，含渠道留痕 2 条）
--     幂等键 = 标题（notification 无标题唯一键，重跑时 NOT EXISTS 按标题拦截）；
--     seq 取 MAX(seq)+行号保证唯一递增，仅在实际插入时计算。
--     type 取业务事件口径（WORK_ORDER / LEASE / NOTICE / FEEDBACK 前缀事件见
--     NotificationService 调用方，此处仅作补拉/已读断言锚点，type 值不参与断言）。
SET @seq_base = (SELECT COALESCE(MAX(seq), 0) FROM notification);

INSERT INTO notification (seq, user_id, community_id, title, content, type, source_type, source_id, channels, is_read, created_at)
SELECT @seq_base + 1, @test_resident_id, @test_community_id,
       'TEST-锚点通知1（未读，无留痕）', '50 阶段预置：未读通知，验证列表与未读数。',
       'SYSTEM', NULL, NULL, 'WEBSOCKET', 0, DATE_SUB(NOW(), INTERVAL 3 DAY)
WHERE NOT EXISTS (SELECT 1 FROM notification WHERE title = 'TEST-锚点通知1（未读，无留痕）');

INSERT INTO notification (seq, user_id, community_id, title, content, type, source_type, source_id, channels, is_read, created_at)
SELECT @seq_base + 2, @test_resident_id, @test_community_id,
       'TEST-锚点通知2（未读，带 EMAIL 留痕）', '50 阶段预置：渠道留痕锚点（EMAIL）。',
       'SYSTEM', NULL, NULL, 'WEBSOCKET,EMAIL', 0, DATE_SUB(NOW(), INTERVAL 2 DAY)
WHERE NOT EXISTS (SELECT 1 FROM notification WHERE title = 'TEST-锚点通知2（未读，带 EMAIL 留痕）');

INSERT INTO notification (seq, user_id, community_id, title, content, type, source_type, source_id, channels, is_read, created_at)
SELECT @seq_base + 3, @test_resident_id, @test_community_id,
       'TEST-锚点通知3（未读，带 SMS 留痕）', '50 阶段预置：渠道留痕锚点（SMS）。',
       'SYSTEM', NULL, NULL, 'WEBSOCKET,SMS', 0, DATE_SUB(NOW(), INTERVAL 1 DAY)
WHERE NOT EXISTS (SELECT 1 FROM notification WHERE title = 'TEST-锚点通知3（未读，带 SMS 留痕）');

INSERT INTO notification (seq, user_id, community_id, title, content, type, source_type, source_id, channels, is_read, created_at)
SELECT @seq_base + 4, @test_resident_id, @test_community_id,
       'TEST-锚点通知4（已读）', '50 阶段预置：已读通知，验证已读/未读状态过滤。',
       'SYSTEM', NULL, NULL, 'WEBSOCKET', 1, DATE_SUB(NOW(), INTERVAL 5 DAY)
WHERE NOT EXISTS (SELECT 1 FROM notification WHERE title = 'TEST-锚点通知4（已读）');

-- 渠道留痕（与通知 2/3 对应：已触发的模拟渠道发送记录；幂等键 = 通知ID+渠道）
INSERT INTO notification_channel_log (notification_id, channel, status, sent_time)
SELECT n.id, 'EMAIL', 'SUCCESS', DATE_SUB(NOW(), INTERVAL 2 DAY)
FROM notification n
WHERE n.title = 'TEST-锚点通知2（未读，带 EMAIL 留痕）'
  AND NOT EXISTS (
      SELECT 1 FROM notification_channel_log l
      WHERE l.notification_id = n.id AND l.channel = 'EMAIL'
  );

INSERT INTO notification_channel_log (notification_id, channel, status, sent_time)
SELECT n.id, 'SMS', 'SUCCESS', DATE_SUB(NOW(), INTERVAL 1 DAY)
FROM notification n
WHERE n.title = 'TEST-锚点通知3（未读，带 SMS 留痕）'
  AND NOT EXISTS (
      SELECT 1 FROM notification_channel_log l
      WHERE l.notification_id = n.id AND l.channel = 'SMS'
  );

-- -----------------------------------------------------------------------------
-- 7. 执行后自检（只读，预期行数）
-- -----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM community WHERE name LIKE 'TEST-%';                    -- 1
-- SELECT COUNT(*) FROM house WHERE community_id = @test_community_id;         -- 6
-- SELECT COUNT(*) FROM sys_user WHERE username LIKE 'test_%';                 -- 4
-- SELECT COUNT(*) FROM resident WHERE username LIKE 'test_%';                 -- 3
-- SELECT COUNT(*) FROM resident WHERE username LIKE 'perf_resident_%';        -- 200
-- SELECT COUNT(*) FROM resource_timeslot WHERE resource_id = @test_conflict_resource_id;  -- 70（7 天 × 10 档）
-- SELECT COUNT(*) FROM housing_timeslot WHERE housing_id = @test_housing_id;  -- 63（7 天 × 9 档）
-- SELECT COUNT(*) FROM notification WHERE title LIKE 'TEST-锚点%';            -- 4
-- SELECT COUNT(*) FROM notification_channel_log;                              -- ≥ 2

-- -----------------------------------------------------------------------------
-- 脚本结束
-- -----------------------------------------------------------------------------
