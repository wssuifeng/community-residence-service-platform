-- =============================================================================
-- 05_clean_seed_realistic.sql · 50 系统测试 · 步骤 4/5（取代 04 的展示数据口径）
-- =============================================================================
-- 用途：把「开发库 community_residence」与「测试库 community_residence_test」
--       重置为 **Flyway V1~V31 + 本脚本** 的干净、可演示、像真实社区的数据。
--       用户口径（2026-09-21）：「数据库清理一下，把那些杂七杂八的测试库数据都
--       清理，留几个干净像真实社区名字的社区数据进去」。
--
-- 与 04_smoke_showcase_data.sql 的区别（该脚本已退役，文件保留仅作历史留痕）：
--   04 造出了月租 ¥0.1 / 押金 ¥0.1、test_admin2、200+ 压测居民等**不像真实社区**
--   的数据；本脚本所有金额均落在**月租 2800~7800 元**的真实区间（见文末自校验），
--   账号与姓名均为可对外的正常口径。
--
-- -----------------------------------------------------------------------------
-- 执行前置（两库通用）
-- -----------------------------------------------------------------------------
--   1) 清空目标库：DROP DATABASE + CREATE DATABASE（utf8mb4 / utf8mb4_unicode_ci）
--      —— 见 00_create_test_db.sql 与 03_cleanup.sql 的口径 A；
--   2) 启动后端一次，令 Flyway 应用 **V1~V31（54 张业务表 + Flyway 历史表
--      `flyway_schema_history`，库内 `information_schema` 计数共 55）**，
--      SPRING_DATASOURCE_URL 指向目标库（不改代码，环境变量覆盖 JDBC URL）：
--        Windows: set SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/<db>?characterEncoding=UTF-8^&serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true
--        （同一方式覆盖 DB_USERNAME/DB_PASSWORD/JWT_SECRET，启动命令不变）
--      Flyway 完成即可停掉后端——本脚本是纯数据脚本，不需要后端在线；
--   3) 导入本脚本（**必须显式 utf8mb4，且不要走 PowerShell 管道**——此前踩过
--      「管道重编码 → Data too long」的坑）：
--        cmd /c "mysql --default-character-set=utf8mb4 -u root -p community_residence_test < 05_clean_seed_realistic.sql"
--        （密码取自环境变量，勿写进任何文件）
--
-- -----------------------------------------------------------------------------
-- 幂等 / 可重复执行
-- -----------------------------------------------------------------------------
--   脚本开头在 SET FOREIGN_KEY_CHECKS=0 下 DELETE 全部业务数据（保留两张表：
--   flyway_schema_history 迁移历史、sys_config 全局配置；保留 V3 的超管 sys_user.id=1），
--   随后以**显式主键**重建全部数据 ⇒ 反复执行结果完全一致，不依赖 Flyway 版本号，
--   也不产生重复行。数据段整体包在事务里（全部 InnoDB、无 DDL），失败自动回滚。
--
-- -----------------------------------------------------------------------------
-- 预置内容一览（3 社区 / 16 单元 / 80 户 / 5 居民 / 6 租约 / 3 工单）
-- -----------------------------------------------------------------------------
--   社区 1 阳光花园社区（幸福路 88 号，多层 6 层，人工审核入住申请）
--   社区 2 翠湖名庭社区（湖滨大道 216 号，多层 4 层，**开启入住申请自动通过**）
--   社区 3 滨江雅苑社区（滨江东路 15 号，多层 5 层，江景房源，租金最高档）
--   结构：社区 → 楼栋 → 单元 → 房屋（生成规则见「2. 社区结构」段注释）；
--   月租：2800 / 3200 / 3800 / 3900 / 4500 / 4800 / 5500 / 6200 / 7800 元（区间 2800~7800）；
--   押金：押一付一 = 月租 × 1（社区 1/2）；押二付三 = 月租 × 2（社区 3）；
--   工单：3 张——1 待受理（PENDING，社区 1 张伟提交，R74「管理端受理」手测起手单）
--         + 1 处理中（IN_PROGRESS，社区 1）+ 1 已完成（COMPLETED，社区 3，含评价）。
--
-- -----------------------------------------------------------------------------
-- 账号（密码哈希与 V3/V6 完全一致，登录口令未变）
-- -----------------------------------------------------------------------------
--   superadmin / Admin@123456   超级管理员（V3 建，本脚本保留不动）
--   admin1     / Admin123456    社区管理员，绑定社区 1、社区 3
--   admin2     / Admin123456    社区管理员，绑定社区 2（第二社区管理员）
--   staff1     / Staff123456    服务人员，常驻社区 1、社区 3
--   staff2     / Staff123456    服务人员，常驻社区 2
--   resident1  / Resident123456 居民（张伟），社区 1 房屋 101 在租（首期未支付）
--   resident2  / Resident123456 居民（李思远），社区 3 在租（即将到期，首期未支付）+ 社区 2 待签约
--   resident3  / Resident123456 居民（王雅静），社区 3 业主自住
--   resident4  / Resident123456 居民（陈志强），社区 2 在租且**租期已过**（5606 到期门禁样本）
--   resident5  / Resident123456 居民（周雅琴），社区 1 在租且**首期已支付**（5904 样本）
--
-- -----------------------------------------------------------------------------
-- 租约矩阵（6 条，覆盖首期支付判定的全部分支，供接口预检与用户手测取样本）
-- -----------------------------------------------------------------------------
--   id=1 居民1 社区1 ACTIVE  2025-07-01~2027-06-30 2800/2800 未到期、首期未付 → 发起首期 200（成功路径）
--   id=2 居民2 社区3 ACTIVE  2025-10-12~2026-10-11 7800/15600 即将到期（≈20 天）、首期未付 → 200 + 到期标注
--   id=3 居民2 社区2 TO_SIGN 2026-10-15~2027-10-14 3200/3200  待签约（入住申请自动通过）→ 承租确认链路样本
--   id=4 居民1 社区1 MOVED_OUT 2023-08-01~2024-07-31 3800/3800 已搬出 + FIRST+SUCCESS 历史单 → 5602（状态优先）
--   id=5 居民4 社区2 ACTIVE  2025-09-01~2026-08-31 5500/5500 **止期早于当天** → 发起首期 5606（到期门禁）
--   id=6 居民5 社区1 ACTIVE  2026-01-01~2026-12-31 3800/3800 未到期 + FIRST+SUCCESS → 发起首期 5904（首期只收一次）
--
-- -----------------------------------------------------------------------------
-- 四者对齐口径（社区归属一致性，接口预检曾发现租约 3 越社区）
-- -----------------------------------------------------------------------------
--   同一居民可在多个社区存在居住关系（租客在 A 社区在租、在 B 社区待签约），但
--   「租约.community_id / 挂牌.community_id / 入住申请.community_id / 居住关系.community_id
--    必须与其 house 所属社区一致」，且「运营数据的操作人/管理员必须是该社区的管理员」。
--   脚本末尾 §12 有对应的自校验 SQL（全部应返回 0）。
--
-- -----------------------------------------------------------------------------
-- 刻意不预置的两类数据（避免把不足写进演示数据，详见 50 阶段 README）
-- -----------------------------------------------------------------------------
--   · notification：seq 列有唯一键 uk_seq，且新通知的 seq 由 Redis 计数器
--     notification:seq 递增；预置行与计数器不同步会导致「新建通知撞唯一键」。
--     通知中心保持为空，由登录后的真实业务动作产生。
--   · conversation / conversation_participant：participant.user_id 同时承载
--     resident.id 与 sys_user.id，而展示时「先查 sys_user 再查 resident」
--     （ConversationService#participantProfiles），居民 1 与超管 1 撞号会被显示成
--     「超级管理员」——DEF-067 同源的既存身份空间歧义，未在本次数据任务中修，
--     故不预置会话，租约群聊字段一律为空。
-- =============================================================================

SET NAMES utf8mb4;

-- 安全断言：确认当前连接库（执行前人工核对，防误连生产/他人库）
SELECT DATABASE() AS current_db, @@hostname AS host, @@port AS port;

-- =============================================================================
-- 1. 清空（保留 flyway_schema_history / sys_config / 超管 sys_user.id=1）
--    ⚠ 维护约定：**此后每个新增的 Flyway 迁移若建了新表，必须把该表加进本清单**，
--      否则上一次手测/E2E 的残留行会跨轮存活，污染下一轮手测
--      （V22 的 lease_deposit_refund 就漏登过，导致押金退款单残留 4 行）。
--    ⚠ 同时检查新迁移是否新增了"指向其它表的关联列"：本清单是 DELETE + 重新 INSERT，
--      关联列随重新插入自然归 NULL（如 lease_record.conversation_id、
--      residence_application.lease_id），无需手工清值。
-- =============================================================================
SET FOREIGN_KEY_CHECKS = 0;

DELETE FROM notification_channel_log;
DELETE FROM notification;
DELETE FROM conversation_message;
DELETE FROM conversation_participant;
DELETE FROM conversation;
DELETE FROM viewing_message;
DELETE FROM viewing_appointment;
DELETE FROM housing_timeslot;
DELETE FROM housing;
DELETE FROM lease_move_out_application;
DELETE FROM lease_deposit_refund;
DELETE FROM lease_payment;
DELETE FROM lease_agreement;
DELETE FROM agreement_template;
DELETE FROM lease_change_log;
DELETE FROM lease_reminder;
DELETE FROM lease_record;
DELETE FROM residence_application;
DELETE FROM residence_relation;
DELETE FROM resident;
DELETE FROM work_order_evaluation;
DELETE FROM unsatisfied_followup;
DELETE FROM work_order_attachment;
DELETE FROM work_order_process;
DELETE FROM work_order_assignment;
DELETE FROM work_order;
DELETE FROM feedback_message;
DELETE FROM feedback_attachment;
DELETE FROM feedback;
DELETE FROM notice_view_record;
DELETE FROM notice_target;
DELETE FROM notice;
DELETE FROM resource_reservation;
DELETE FROM violation_record;
DELETE FROM resource_timeslot;
DELETE FROM public_resource;
DELETE FROM house_status_history;
DELETE FROM house;
DELETE FROM unit;
DELETE FROM building;
DELETE FROM service_category;
DELETE FROM staff_schedule;
DELETE FROM staff_service_category;
DELETE FROM staff_community;
DELETE FROM statistics_snapshot;
DELETE FROM sys_operation_log;
DELETE FROM sys_admin_community;
DELETE FROM sys_blacklist_log;
DELETE FROM sys_task_log;
DELETE FROM auth_token_blacklist;
DELETE FROM community;
/* V6 演示账号 admin1/staff1 一并重建（姓名改为真实姓名）；V3 超管保留 */
DELETE FROM sys_user WHERE id <> 1;

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- 2. 社区 → 楼栋 → 单元 → 房屋（显式主键，便于后续引用）
-- =============================================================================
START TRANSACTION;

INSERT INTO community (id, name, address, responsible_admin_id, description,
                       status, auto_approve_residence, default_lease_months, created_at, updated_at) VALUES
(1, '阳光花园社区', '示范市幸福路 88 号', NULL,
 '建成于 2012 年的多层住宅社区，共 3 栋楼、6 个单元，绿化率 38%，配套社区食堂与地下车库。',
 'ACTIVE', 0, 12, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 30 DAY),
(2, '翠湖名庭社区', '示范市湖滨大道 216 号', NULL,
 '临湖改善型社区，共 2 栋楼、6 个单元，设业主会所与健身房，入住申请由系统自动通过。',
 'ACTIVE', 1, 12, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 20 DAY),
(3, '滨江雅苑社区', '示范市滨江东路 15 号', NULL,
 '江景高层社区，共 2 栋楼、4 个单元，24 小时安保与管家服务，租期以两年为主。',
 'ACTIVE', 0, 24, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 10 DAY);

/* 楼栋：floors 与实际建模层数一致（多层住宅 4~6 层，1 梯 2~3 户） */
INSERT INTO building (id, community_id, name, floors, description, created_at, updated_at) VALUES
(1, 1, '1 号楼', 6, '多层住宅，1 梯 2 户', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(2, 1, '2 号楼', 6, '多层住宅，1 梯 2 户', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(3, 1, '3 号楼', 6, '多层住宅，1 梯 2 户，临社区中庭', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(4, 2, '1 号楼', 4, '临湖多层，1 梯 3 户', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(5, 2, '2 号楼', 4, '临湖多层，1 梯 3 户', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(6, 3, 'A 座', 5, '江景楼栋，1 梯 2 户', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(7, 3, 'B 座', 5, '江景楼栋，1 梯 2 户', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY);

INSERT INTO unit (id, building_id, community_id, name, description, created_at, updated_at) VALUES
(1, 1, 1, '1 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(2, 1, 1, '2 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(3, 2, 1, '1 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(4, 2, 1, '2 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(5, 3, 1, '1 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(6, 3, 1, '2 单元', NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(7, 4, 2, '1 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(8, 4, 2, '2 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(9, 4, 2, '3 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(10, 5, 2, '1 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(11, 5, 2, '2 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(12, 5, 2, '3 单元', NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(13, 6, 3, '1 单元', NULL, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(14, 6, 3, '2 单元', NULL, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(15, 7, 3, '1 单元', NULL, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(16, 7, 3, '2 单元', NULL, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY);

/* 房屋生成规则：每个单元 1 户/层，房号 = 层号 + "0" + 单元序号（如 1 单元 3 层 = 301）；
   建模层数即 building.floors（社区 1 = 6 层、社区 2 = 4 层、社区 3 = 5 层）⇒ 共 80 户；
   户型按层号循环：89.5㎡ 两室一厅 / 120㎡ 三室两厅 / 143.5㎡ 四室两厅；朝向按层号 4 循环。 */
INSERT INTO house (unit_id, community_id, house_number, floor, area, layout,
                   orientation, status, created_at, updated_at)
SELECT u.id,
       u.community_id,
       CONCAT(f.n, '0', LEFT(u.name, 1)),
       f.n,
       CASE MOD(f.n, 3) WHEN 1 THEN 89.50 WHEN 2 THEN 120.00 ELSE 143.50 END,
       CASE MOD(f.n, 3) WHEN 1 THEN '2室1厅1卫' WHEN 2 THEN '3室2厅1卫' ELSE '4室2厅2卫' END,
       CASE MOD(f.n, 4) WHEN 1 THEN 'SOUTH' WHEN 2 THEN 'EAST' WHEN 3 THEN 'NORTH' ELSE 'WEST' END,
       'VACANT',
       NOW() - INTERVAL 400 DAY,
       NOW() - INTERVAL 400 DAY
FROM unit u
JOIN (SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) f
  ON f.n <= CASE u.building_id WHEN 1 THEN 6 WHEN 2 THEN 6 WHEN 3 THEN 6
                               WHEN 4 THEN 4 WHEN 5 THEN 4
                               WHEN 6 THEN 5 WHEN 7 THEN 5 END;

UPDATE house SET description = CONCAT('标准层住宅，', layout, '，建筑面积 ', area, ' ㎡，朝向 ',
       CASE orientation WHEN 'SOUTH' THEN '南' WHEN 'EAST' THEN '东'
                        WHEN 'NORTH' THEN '北' ELSE '西' END);

/* 少量非空置房源，覆盖房屋状态枚举（不影响下方挂牌与租约引用）；
   引用一律用 (unit_id, floor) 定位——房号末位是单元序号，2 单元 3 层是 302 而非 301 */
UPDATE house SET status = 'OCCUPIED'    WHERE unit_id = 1  AND floor = 1;  -- 1 单元 101 张伟在租
UPDATE house SET status = 'OCCUPIED'    WHERE unit_id = 13 AND floor = 3;  -- 1 单元 301 李思远在租
UPDATE house SET status = 'OCCUPIED'    WHERE unit_id = 8  AND floor = 3;  -- 2 单元 302 陈志强在租（租期已过未处理）
UPDATE house SET status = 'OCCUPIED'    WHERE unit_id = 4  AND floor = 2;  -- 2 单元 202 周雅琴在租
UPDATE house SET status = 'MAINTENANCE' WHERE unit_id = 5  AND floor = 6;  -- 1 单元 601 屋顶防水返修
UPDATE house SET status = 'RESERVED'    WHERE unit_id = 8  AND floor = 2;  -- 2 单元 202 物业预留（临时用房）

/* 状态变更留痕：操作人取房屋所属社区的管理员（社区 1/3 = admin1、社区 2 = admin2），避免跨社区操作人 */
INSERT INTO house_status_history (house_id, community_id, old_status, new_status, operator_id, remark, created_at)
SELECT h.id, h.community_id, 'VACANT', h.status,
       CASE h.community_id WHEN 2 THEN 5 ELSE 2 END,
       '社区房屋状态维护（种子数据）', NOW() - INTERVAL 5 DAY
FROM house h WHERE h.status <> 'VACANT';

-- =============================================================================
-- 3. 账号：社区管理员 / 服务人员（超管 id=1 由 V3 保留）
-- =============================================================================
INSERT INTO sys_user (id, username, password_hash, real_name, phone, email, role, status, created_at, updated_at) VALUES
(2, 'admin1', '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK', '陈国强', '13805710101',
 'chenguoqiang@sunshine-garden.example.com', 'ADMIN', 'ACTIVE', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 30 DAY),
(3, 'staff1', '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu', '刘伟民', '13805710201',
 'liuweimin@sunshine-garden.example.com', 'STAFF', 'ACTIVE', NOW() - INTERVAL 380 DAY, NOW() - INTERVAL 30 DAY),
(4, 'staff2', '$2a$10$AIz0t0kXJFTSsc4P191vCONeL7BdyaXLhwYSa9jPffI5xYecAdpeu', '王建军', '13805710202',
 'wangjianjun@cuihu-mingting.example.com', 'STAFF', 'ACTIVE', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 30 DAY),
(5, 'admin2', '$2a$10$kj977BcADCP9XTSC0aitbudmApBbZbflmkJephDL8xTygdJEjwCwK', '赵敏', '13805710102',
 'zhaomin@cuihu-mingting.example.com', 'ADMIN', 'ACTIVE', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 30 DAY);

/* V31责任人绑定真实ADMIN账号；账号创建后写入，避免悬空外键。 */
UPDATE community SET responsible_admin_id = CASE id WHEN 2 THEN 5 ELSE 2 END WHERE id IN (1, 2, 3);

INSERT INTO sys_admin_community (admin_id, community_id, created_at) VALUES
(2, 1, NOW() - INTERVAL 400 DAY),
(2, 3, NOW() - INTERVAL 200 DAY),
(5, 2, NOW() - INTERVAL 300 DAY);

INSERT INTO staff_community (staff_id, community_id, created_at) VALUES
(3, 1, NOW() - INTERVAL 380 DAY),
(3, 3, NOW() - INTERVAL 200 DAY),
(4, 2, NOW() - INTERVAL 300 DAY);

-- =============================================================================
-- 4. 居民与居住关系
-- =============================================================================
INSERT INTO resident (id, username, password_hash, real_name, phone, email, status, created_at, updated_at) VALUES
(1, 'resident1', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC', '张伟', '13905710101',
 'zhangwei@example.com', 'ACTIVE', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 30 DAY),
(2, 'resident2', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC', '李思远', '13905710102',
 'lisiyuan@example.com', 'ACTIVE', NOW() - INTERVAL 350 DAY, NOW() - INTERVAL 20 DAY),
(3, 'resident3', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC', '王雅静', '13905710103',
 'wangyajing@example.com', 'ACTIVE', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 20 DAY),
(4, 'resident4', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC', '陈志强', '13905710104',
 'chenzhiqiang@example.com', 'ACTIVE', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 20 DAY),
(5, 'resident5', '$2a$10$wxp65ShQp.QONrPmTihKQe.MzpwzEJ7ZeSugncain4HTj/YMoA5TC', '周雅琴', '13905710105',
 'zhouyaqin@example.com', 'ACTIVE', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 20 DAY);

/* 居住关系：与「入住申请 → 审核通过 → 生成居住关系 + 待签约租约」的业务流自洽——
   居民 2 在社区 3 在租、在社区 2 待签约，故两处均有居住关系（社区 3 为主要居住地） */
INSERT INTO residence_relation (id, resident_id, community_id, house_id, relation_type, move_in_date,
                                move_out_date, is_primary, created_at, updated_at) VALUES
(1, 1, 1, (SELECT id FROM house WHERE unit_id = 1  AND floor = 1), 'TENANT', '2025-07-01', NULL, 1,
    NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(2, 2, 3, (SELECT id FROM house WHERE unit_id = 13 AND floor = 3), 'TENANT', '2025-10-12', NULL, 1,
    NOW() - INTERVAL 350 DAY, NOW() - INTERVAL 350 DAY),
(3, 3, 3, (SELECT id FROM house WHERE unit_id = 13 AND floor = 4), 'OWNER',  '2026-02-08', NULL, 1,
    NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(4, 2, 2, (SELECT id FROM house WHERE unit_id = 7  AND floor = 4), 'TENANT', '2026-10-15', NULL, 0,
    NOW() - INTERVAL 12 DAY, NOW() - INTERVAL 12 DAY),
(5, 4, 2, (SELECT id FROM house WHERE unit_id = 8  AND floor = 3), 'TENANT', '2025-09-01', NULL, 1,
    NOW() - INTERVAL 390 DAY, NOW() - INTERVAL 390 DAY),
(6, 5, 1, (SELECT id FROM house WHERE unit_id = 4  AND floor = 2), 'TENANT', '2026-01-01', NULL, 1,
    NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY);

INSERT INTO residence_application (id, resident_id, community_id, house_id, lease_id, relation_type, status, remark,
                                   reviewer_id, review_time, review_remark, created_at, updated_at) VALUES
/* lease_id（V26 强关联）：APPROVED 的申请必须指向它生成的那份租约（此处 1 → 租约 3），
   使"申请 → 租约 → 会话"一跳可达；非 APPROVED 申请保持 NULL */
(1, 2, 2, (SELECT id FROM house WHERE unit_id = 7 AND floor = 4), 3, 'TENANT', 'APPROVED',
    '工作调动至湖滨片区，希望 10 月中旬入住', NULL, NOW() - INTERVAL 12 DAY,
    '该社区已开启入住申请自动通过，系统自动审核通过', NOW() - INTERVAL 12 DAY, NOW() - INTERVAL 12 DAY),
(2, 3, 1, (SELECT id FROM house WHERE unit_id = 3 AND floor = 2), NULL, 'OWNER', 'WITHDRAWN',
    '原计划在阳光花园社区购房', NULL, NULL, NULL, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 25 DAY);

-- =============================================================================
-- 5. 服务类别目录（7 类 × 3 社区，口径同 V7）
-- =============================================================================
INSERT INTO service_category (id, community_id, name, description, parent_id, sort_order, is_active, created_at, updated_at) VALUES
(1, 1, '室内维修', '门窗、家具、墙面等室内设施维修', NULL, 1, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(2, 1, '公共设施报修', '电梯、门禁、路灯等公共设施故障', NULL, 2, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(3, 1, '公共区域卫生', '楼道、车库、绿化带卫生问题', NULL, 3, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(4, 1, '水电管道', '给排水、电路、燃气管道问题', NULL, 4, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(5, 1, '电梯楼道', '电梯运行、楼道照明与门禁', NULL, 5, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(6, 1, '安全隐患上报', '消防通道占用、外墙脱落等隐患', NULL, 6, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(7, 1, '其他', '未归入以上类别的服务申请', NULL, 7, 1, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(8, 2, '室内维修', '门窗、家具、墙面等室内设施维修', NULL, 1, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(9, 2, '公共设施报修', '电梯、门禁、路灯等公共设施故障', NULL, 2, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(10, 2, '公共区域卫生', '楼道、车库、绿化带卫生问题', NULL, 3, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(11, 2, '水电管道', '给排水、电路、燃气管道问题', NULL, 4, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(12, 2, '电梯楼道', '电梯运行、楼道照明与门禁', NULL, 5, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(13, 2, '安全隐患上报', '消防通道占用、外墙脱落等隐患', NULL, 6, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(14, 2, '其他', '未归入以上类别的服务申请', NULL, 7, 1, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY),
(15, 3, '室内维修', '门窗、家具、墙面等室内设施维修', NULL, 1, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(16, 3, '公共设施报修', '电梯、门禁、路灯等公共设施故障', NULL, 2, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(17, 3, '公共区域卫生', '楼道、车库、绿化带卫生问题', NULL, 3, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(18, 3, '水电管道', '给排水、电路、燃气管道问题', NULL, 4, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(19, 3, '电梯楼道', '电梯运行、楼道照明与门禁', NULL, 5, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(20, 3, '安全隐患上报', '消防通道占用、外墙脱落等隐患', NULL, 6, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY),
(21, 3, '其他', '未归入以上类别的服务申请', NULL, 7, 1, NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 200 DAY);

/* 擅长类别与常驻社区对齐：staff1 覆盖社区 1（1/2/4/5）与社区 3（15 = 社区 3 室内维修），
   staff2 覆盖社区 2（9/11）——保证派单对象在其常驻社区内确实具备对应类别能力 */
INSERT INTO staff_service_category (staff_id, category_id, created_at) VALUES
(3, 4, NOW() - INTERVAL 380 DAY), (3, 2, NOW() - INTERVAL 380 DAY), (3, 5, NOW() - INTERVAL 300 DAY),
(3, 15, NOW() - INTERVAL 200 DAY),
(4, 11, NOW() - INTERVAL 300 DAY), (4, 9, NOW() - INTERVAL 300 DAY);

/* 排班：未来 7 天，五人班次轮转（早班/午班/全天/休息）；
   created_by 取排班社区绑定的管理员（社区 1 = admin1(id 2)、社区 2 = admin2(id 5)），
   口径同上方 house_status_history，避免跨社区操作人（§12.1 有对应自校验） */
INSERT INTO staff_schedule (staff_id, community_id, work_date, shift_type, start_time, end_time, remark, created_by, created_at, updated_at)
SELECT s.staff_id, s.community_id, DATE_ADD(CURDATE(), INTERVAL d.n DAY),
       CASE MOD(d.n + s.shift_offset, 4) WHEN 0 THEN 'MORNING' WHEN 1 THEN 'AFTERNOON'
                                         WHEN 2 THEN 'FULL' ELSE 'REST' END,
       CASE MOD(d.n + s.shift_offset, 4) WHEN 0 THEN '08:00:00' WHEN 1 THEN '13:00:00'
                                         WHEN 2 THEN '08:00:00' ELSE NULL END,
       CASE MOD(d.n + s.shift_offset, 4) WHEN 0 THEN '13:00:00' WHEN 1 THEN '18:00:00'
                                         WHEN 2 THEN '18:00:00' ELSE NULL END,
       CASE MOD(d.n + s.shift_offset, 4) WHEN 3 THEN '轮休' ELSE NULL END,
       CASE s.community_id WHEN 2 THEN 5 ELSE 2 END, NOW(), NOW()
FROM (SELECT 3 AS staff_id, 1 AS community_id, 0 AS shift_offset
      UNION ALL SELECT 4, 2, 2) s
JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6) d;

-- =============================================================================
-- 6. 公共资源与预约时段
-- =============================================================================
INSERT INTO public_resource (id, community_id, name, type, location, capacity, slot_unit, description, created_at, updated_at) VALUES
(1, 1, '社区健身房', 'GYM', '3 号楼 1 层东侧', 20, 60, '跑步机、椭圆机与力量器械区，需穿运动鞋入场', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 60 DAY),
(2, 1, '多功能会议室', 'MEETING_ROOM', '物业服务中心 2 层', 12, 30, '配投影与白板，可容纳 12 人，适合业主议事', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 60 DAY),
(3, 1, '地下车库 A 区车位', 'PARKING', '地下 1 层 A 区', 40, 60, '业主与租户按月登记使用', NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 60 DAY),
(4, 2, '翠湖健身会所', 'GYM', '业主会所 1 层', 15, 60, '临湖跑步机区与瑜伽区', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 60 DAY),
(5, 2, '湖景会议室', 'MEETING_ROOM', '业主会所 2 层', 20, 30, '可容纳 20 人，配视频会议设备', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 60 DAY),
(6, 3, '江景健身会所', 'GYM', '会所 3 层', 25, 60, '落地窗江景跑步区与器械区', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 60 DAY),
(7, 3, '地下车库 B 区车位', 'PARKING', '地下 2 层 B 区', 60, 60, '含新能源充电车位 12 个', NOW() - INTERVAL 200 DAY, NOW() - INTERVAL 60 DAY);

/* 时段：工作日 09:00-12:00 / 14:00-18:00；健身房另开放周末上午 */
INSERT INTO resource_timeslot (resource_id, community_id, day_of_week, start_time, end_time, is_available, created_at, updated_at)
SELECT r.id, r.community_id, d.dow, t.start_time, t.end_time, 1, NOW(), NOW()
FROM public_resource r
JOIN (SELECT 1 AS dow UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5) d
JOIN (SELECT '09:00:00' AS start_time, '12:00:00' AS end_time
      UNION ALL SELECT '14:00:00', '18:00:00') t
WHERE r.community_id IN (1, 2, 3);

INSERT INTO resource_timeslot (resource_id, community_id, day_of_week, start_time, end_time, is_available, created_at, updated_at)
SELECT r.id, r.community_id, d.dow, '09:00:00', '12:00:00', 1, NOW(), NOW()
FROM public_resource r
JOIN (SELECT 6 AS dow UNION ALL SELECT 7) d
WHERE r.type = 'GYM';

/* 预约记录：slot_occupied 是 STORED GENERATED 列（占用状态自动派生），不可显式赋值 */
INSERT INTO resource_reservation (id, user_id, resource_id, community_id, reserve_date, start_time, end_time,
                                  status, purpose, contact_phone, remark, created_at, updated_at) VALUES
(1, 1, 1, 1, DATE_ADD(CURDATE(), INTERVAL 1 DAY), '09:00:00', '10:00:00', 'RESERVED',
    '日常健身', '13905710101', NULL, NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY);

-- =============================================================================
-- 7. 房源挂牌与看房时段（游客端可见）
-- =============================================================================
INSERT INTO housing (id, community_id, house_id, title, description, monthly_rent, deposit, rent_type, layout,
                     images, status, view_count, publish_time, created_at, updated_at) VALUES
(1, 1, (SELECT id FROM house WHERE unit_id = 1 AND floor = 2),
 '阳光花园社区 3室2厅1卫 精装修 拎包入住',
 '南北通透三居，家电家具齐全，小区内配社区食堂与地下车库，步行 8 分钟到地铁 2 号线幸福路站。',
 3800.00, 3800.00, 'RENT', '3室2厅1卫', NULL, 'AVAILABLE', 126, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 20 DAY, NOW() - INTERVAL 2 DAY),
(2, 1, (SELECT id FROM house WHERE unit_id = 2 AND floor = 3),
 '阳光花园社区 4室2厅2卫 中庭景观房',
 '143.5㎡ 大四居，客厅朝南，正对社区中庭花园，适合三代同堂，业主直租无中介费。',
 4800.00, 4800.00, 'RENT', '4室2厅2卫', NULL, 'AVAILABLE', 88, NOW() - INTERVAL 15 DAY, NOW() - INTERVAL 15 DAY, NOW() - INTERVAL 1 DAY),
(3, 1, (SELECT id FROM house WHERE unit_id = 3 AND floor = 1),
 '阳光花园社区 2室1厅1卫 一楼带小院',
 '89.5㎡ 两居，一楼带 12㎡ 南向小院，适合有老人或宠物的家庭，租金含物业费。',
 2800.00, 2800.00, 'RENT', '2室1厅1卫', NULL, 'AVAILABLE', 203, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 3 DAY),
(4, 2, (SELECT id FROM house WHERE unit_id = 7 AND floor = 4),
 '翠湖名庭社区 2室1厅1卫 临湖景观房',
 '推窗见湖，精装两居，业主会所健身房与会议室对租户开放，已定待签约。',
 3200.00, 3200.00, 'RENT', '2室1厅1卫', NULL, 'RESERVED', 41, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 14 DAY, NOW() - INTERVAL 12 DAY),
(5, 2, (SELECT id FROM house WHERE unit_id = 11 AND floor = 2),
 '翠湖名庭社区 3室2厅1卫 湖景三居',
 '120㎡ 三居，客厅正对翠湖公园，社区内设幼儿园，适合长期自住家庭。',
 4500.00, 4500.00, 'RENT', '3室2厅1卫', NULL, 'AVAILABLE', 57, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 10 DAY, NOW() - INTERVAL 1 DAY),
(6, 3, (SELECT id FROM house WHERE unit_id = 13 AND floor = 1),
 '滨江雅苑社区 2室1厅1卫 江景两居',
 '89.5㎡ 江景两居，24 小时管家服务，地下车库含新能源充电车位。',
 3900.00, 3900.00, 'RENT', '2室1厅1卫', NULL, 'AVAILABLE', 64, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 8 DAY, NOW() - INTERVAL 1 DAY),
(7, 3, (SELECT id FROM house WHERE unit_id = 16 AND floor = 3),
 '滨江雅苑社区 4室2厅2卫 高层江景大平层',
 '143.5㎡ 四居大平层，270° 江景，押二付三，含 1 个地下车位使用权。',
 7800.00, 15600.00, 'RENT', '4室2厅2卫', NULL, 'AVAILABLE', 35, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 6 DAY, NOW() - INTERVAL 1 DAY);

/* 看房时段：工作日 5 个 1 小时段 + 周末上午 2 段 */
INSERT INTO housing_timeslot (housing_id, day_of_week, start_time, end_time, created_at, updated_at)
SELECT h.id, d.dow, t.start_time, t.end_time, NOW(), NOW()
FROM housing h
JOIN (SELECT 1 AS dow UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7) d
JOIN (SELECT '09:00:00' AS start_time, '10:00:00' AS end_time
      UNION ALL SELECT '10:00:00', '11:00:00'
      UNION ALL SELECT '14:00:00', '15:00:00'
      UNION ALL SELECT '15:00:00', '16:00:00'
      UNION ALL SELECT '16:00:00', '17:00:00') t
WHERE h.status = 'AVAILABLE';

/* 看房预约：slot_occupied 同样是 STORED GENERATED 列（状态为待确认/已预约时自动置 1） */
INSERT INTO viewing_appointment (id, user_id, visitor_name, visitor_phone, assigned_staff_id, conversation_id, lease_id,
                                 housing_id, community_id, appointment_date, start_time, end_time, status,
                                 contact_phone, remark, created_at, updated_at) VALUES
(1, NULL, '周晓明', '13705710801', NULL, NULL, NULL, 1, 1,
    DATE_ADD(CURDATE(), INTERVAL 2 DAY), '10:00:00', '11:00:00', 'TO_CONFIRM', '13705710801',
    '游客线上预约，希望了解采光与物业费', NOW() - INTERVAL 6 HOUR, NOW() - INTERVAL 6 HOUR);

-- =============================================================================
-- 8. 公告（含 GUEST 游客可见，R12/DEF-046）
-- =============================================================================
INSERT INTO notice (id, title, content, publisher_id, status, publish_time, start_time, end_time,
                    view_count, is_pinned, priority, type, created_at, updated_at) VALUES
(1, '阳光花园社区 2026 年国庆节放假安排通知',
 '各位业主、租户：国庆节期间（10 月 1 日至 10 月 7 日）物业服务中心值班时间为 9:00-17:00，'
 '报修请通过系统提交或拨打值班电话 0571-88880001。10 月 1 日上午 9:00 在社区中庭举行升旗仪式，欢迎参加。',
 2, 'PUBLISHED', NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY, NOW() + INTERVAL 27 DAY,
 218, 1, 'HIGH', 'ANNOUNCEMENT', NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 3 DAY),
(2, '1 号楼电梯年度检修停梯通知',
 '1 号楼电梯将于本周六 8:00-17:00 进行年度安全检修，期间停梯。请高层住户提前安排出行，'
 '行动不便的老人可由物业协助搬运物品。给您带来不便，敬请谅解。',
 2, 'PUBLISHED', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 14 DAY,
 96, 0, 'URGENT', 'ANNOUNCEMENT', NOW() - INTERVAL 1 DAY, NOW() - INTERVAL 1 DAY),
(3, '翠湖名庭社区中秋游园活动报名通知',
 '社区定于本周日 15:00 在业主会所前广场举办中秋游园活动，含猜灯谜、投壶与月饼 DIY，'
 '限额 60 户，请在系统「资源预约」中报名登记。',
 5, 'PUBLISHED', NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY, NOW() + INTERVAL 9 DAY,
 132, 0, 'NORMAL', 'ANNOUNCEMENT', NOW() - INTERVAL 5 DAY, NOW() - INTERVAL 5 DAY),
(4, '关于规范社区电动车停放与充电的公告',
 '为消除消防隐患，社区内电动车请停放至指定车棚充电，严禁楼道停放与飞线充电。'
 '本周起物业将每日巡查，感谢配合。',
 2, 'PUBLISHED', NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY, NOW() + INTERVAL 23 DAY,
 305, 1, 'HIGH', 'ANNOUNCEMENT', NOW() - INTERVAL 7 DAY, NOW() - INTERVAL 7 DAY);

INSERT INTO notice_target (notice_id, target_type, target_id, created_at) VALUES
(1, 'COMMUNITY', 1, NOW() - INTERVAL 3 DAY),
(2, 'BUILDING', 1, NOW() - INTERVAL 1 DAY),
(3, 'COMMUNITY', 2, NOW() - INTERVAL 5 DAY),
(4, 'COMMUNITY', 3, NOW() - INTERVAL 7 DAY),
(4, 'GUEST', 0, NOW() - INTERVAL 7 DAY);

-- =============================================================================
-- 9. 工单 / 反馈（各状态底料）
--   工单 3 张：id=1 处理中（社区 1）、id=2 已完成（社区 3，含评价）、
--   **id=3 待受理 PENDING（社区 1，居民 1 张伟提交）**——R74 工单完整链路手测
--   （CF-E4）从「管理端受理」起手必须有待受理单，故 2026-09-22 补入。
-- =============================================================================
INSERT INTO work_order (id, order_no, resident_id, community_id, category_id, title, content, contact_phone,
                        address, status, priority, created_at, updated_at) VALUES
(1, 'WO202609180001', 1, 1, 4, '厨房下水管道返味',
 '厨房水槽下水口近一周持续返味，已自行疏通未解决，希望安排师傅上门检查存水弯与主管道。',
 '13905710101', '阳光花园社区 1 号楼 1 单元 101', 'IN_PROGRESS', 'HIGH',
 NOW() - INTERVAL 3 DAY, NOW() - INTERVAL 1 DAY),
(2, 'WO202609100002', 2, 3, 15, '客厅空调不制冷',
 '客厅空调开机后出风不凉，已清洗滤网仍无效，怀疑缺氟，请安排检修。',
 '13905710102', '滨江雅苑社区 A 座 1 单元 301', 'COMPLETED', 'NORMAL',
 NOW() - INTERVAL 11 DAY, NOW() - INTERVAL 6 DAY),
(3, 'WO202609220003', 1, 1, 1, '卫生间马桶冲水无力',
 '主卫马桶按下冲水键后水流很小，需要长按才冲得下去，怀疑进水阀或冲水组件老化，希望安排师傅上门检查。',
 '13905710101', '阳光花园社区 1 号楼 1 单元 101', 'PENDING', 'NORMAL',
 NOW() - INTERVAL 20 MINUTE, NOW() - INTERVAL 20 MINUTE);

INSERT INTO work_order_assignment (work_order_id, assignee_id, assigner_id, assign_time, accept_time, created_at) VALUES
(1, 3, 2, NOW() - INTERVAL 3 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 3 DAY + INTERVAL 3 HOUR, NOW() - INTERVAL 3 DAY),
(2, 3, 2, NOW() - INTERVAL 11 DAY + INTERVAL 1 HOUR, NOW() - INTERVAL 11 DAY + INTERVAL 2 HOUR, NOW() - INTERVAL 11 DAY);

INSERT INTO work_order_process (work_order_id, operator_id, operator_type, action, old_status, new_status, content, created_at) VALUES
(1, 1, 'RESIDENT', 'SUBMIT', NULL, 'PENDING', '厨房下水管道返味，希望尽快处理', NOW() - INTERVAL 3 DAY),
(1, 2, 'ADMIN', 'ASSIGN', 'PENDING', 'ASSIGNED', '派单给刘伟民（水电管道）', NOW() - INTERVAL 3 DAY + INTERVAL 2 HOUR),
(1, 3, 'STAFF', 'ACCEPT', 'ASSIGNED', 'ACCEPTED', '已接单，今天下午上门', NOW() - INTERVAL 3 DAY + INTERVAL 3 HOUR),
(1, 3, 'STAFF', 'PROCESS', 'ACCEPTED', 'IN_PROGRESS', '已检查存水弯，需更换密封件，明日带件复装', NOW() - INTERVAL 1 DAY),
(2, 1 + 1, 'RESIDENT', 'SUBMIT', NULL, 'PENDING', '客厅空调不制冷', NOW() - INTERVAL 11 DAY),
(2, 2, 'ADMIN', 'ASSIGN', 'PENDING', 'ASSIGNED', '派单给刘伟民', NOW() - INTERVAL 11 DAY + INTERVAL 1 HOUR),
(2, 3, 'STAFF', 'ACCEPT', 'ASSIGNED', 'ACCEPTED', '已接单', NOW() - INTERVAL 11 DAY + INTERVAL 2 HOUR),
(2, 3, 'STAFF', 'COMPLETE', 'IN_PROGRESS', 'TO_CONFIRM', '已补充冷媒并清洗外机，制冷恢复正常', NOW() - INTERVAL 6 DAY),
(2, 2, 'ADMIN', 'CONFIRM', 'TO_CONFIRM', 'COMPLETED', '居民已确认完成', NOW() - INTERVAL 6 DAY + INTERVAL 3 HOUR),
(3, 1, 'RESIDENT', 'SUBMIT', NULL, 'PENDING', '卫生间马桶冲水无力，希望尽快安排师傅上门', NOW() - INTERVAL 20 MINUTE);

INSERT INTO work_order_evaluation (work_order_id, resident_id, community_id, rating, is_satisfied, created_at) VALUES
(2, 2, 3, 5, 1, NOW() - INTERVAL 6 DAY + INTERVAL 5 HOUR);

INSERT INTO feedback (id, resident_id, community_id, title, content, category, status, handler_id, created_at, updated_at) VALUES
(1, 1, 1, '建议增加地下车库照明与监控',
 '地下车库 B 区入口几盏灯长期不亮，晚上视线较差，建议一并检查监控覆盖情况。',
 'SUGGESTION', 'IN_SESSION', 2, NOW() - INTERVAL 2 DAY, NOW() - INTERVAL 1 DAY);

INSERT INTO feedback_message (feedback_id, sender_id, sender_type, content, parent_id, created_at) VALUES
(1, 1, 'RESIDENT', '地下车库 B 区入口几盏灯长期不亮，晚上视线较差，建议一并检查监控覆盖情况。', NULL, NOW() - INTERVAL 2 DAY),
(1, 2, 'ADMIN', '您好，已记录并转工程部，本周内完成灯具更换，监控覆盖情况会一并排查后回复。', NULL, NOW() - INTERVAL 1 DAY);

-- =============================================================================
-- 10. 协议模板与租约（含变更历史、历史支付、退租申请）
-- =============================================================================
INSERT INTO agreement_template (id, community_id, name, content, file_name, file_url, file_size, is_default, status, remark, created_at, updated_at) VALUES
(1, NULL, '住宅租赁合同（社区标准模板）',
 '住宅租赁合同\n\n甲方（出租方）：{{communityName}} 物业服务中心\n乙方（承租方）：{{tenantName}}\n\n一、租赁房屋：{{communityName}} {{houseLocation}}（房号 {{houseNumber}}，第 {{floor}} 层）。\n二、租赁期限：自 {{startDate}} 起至 {{endDate}} 止。\n三、租金与押金：月租金人民币 {{monthlyRent}} 元；押金人民币 {{deposit}} 元，租赁关系终止并完成退租验收后原路退还。\n四、双方权利义务：甲方保证房屋及附属设施正常使用并负责公共部位维修；乙方按期支付租金，合理使用房屋，不得擅自改变房屋结构或转租。\n五、其他约定：本协议经双方在系统内确认后生效，确认留痕仅用于系统内流程留档。\n\n租约编号：{{leaseId}}\n签约日期：{{signDate}}',
 NULL, NULL, NULL, 1, 'ACTIVE', '全局默认模板，未单独配置模板的社区均使用本模板',
 NOW() - INTERVAL 250 DAY, NOW() - INTERVAL 250 DAY),
(2, 1, '阳光花园社区住宅租赁合同（2026 版）',
 '阳光花园社区住宅租赁合同\n\n甲方：阳光花园社区物业服务中心\n乙方（承租方）：{{tenantName}}\n\n一、租赁房屋：{{communityName}} {{houseLocation}}。\n二、租赁期限：{{startDate}} 至 {{endDate}}。\n三、月租金 {{monthlyRent}} 元，押金 {{deposit}} 元（押一付一，租金按季预缴）。\n四、乙方享有社区公共资源预约、报修与公告服务；甲方提供 24 小时报修受理。\n五、退租需提前 30 日在系统内提交退租申请。\n\n签约日期：{{signDate}}',
 NULL, NULL, NULL, 0, 'ACTIVE', '社区定制模板，供社区 1 单独选用',
 NOW() - INTERVAL 120 DAY, NOW() - INTERVAL 120 DAY);

/* 押金状态（V22 五态，DEF-113 补齐）：仅有 FIRST+SUCCESS 首期单的租约 4/6 置 PAID
   （口径同 V25 存量回填与运行时首期落账：首期单金额 = 首月租金 + 押金 > 月租，
   押金部分已实缴），其余租约首期未付置 UNPAID；租约 4 虽已搬出且押金已原路退还
   （remark 口径），但退款单刻意不预置（§12 校验「押金退款单」应为 0），状态停在
   实缴口径 PAID，不虚设 REFUNDED */
INSERT INTO lease_record (id, tenant_id, community_id, house_id, start_date, end_date, monthly_rent, deposit, deposit_status,
                          status, contract_url, remark, agreement_status, tenant_confirm_at, tenant_confirm_by,
                          conversation_id, created_at, updated_at) VALUES
(1, 1, 1, (SELECT id FROM house WHERE unit_id = 1 AND floor = 1),
 '2025-07-01', '2027-06-30', 2800.00, 2800.00, 'UNPAID', 'ACTIVE', NULL, '押一付一，租金按季预缴；已在线签署租赁协议',
 'SIGNED', '2025-06-28 10:20:00', 1, NULL, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 30 DAY),
(2, 2, 3, (SELECT id FROM house WHERE unit_id = 13 AND floor = 3),
 '2025-10-12', '2026-10-11', 7800.00, 15600.00, 'UNPAID', 'ACTIVE', NULL, '押二付三，江景房源；租期临近届满，已收到到期提醒',
 'SIGNED', '2025-10-08 15:40:00', 2, NULL, NOW() - INTERVAL 350 DAY, NOW() - INTERVAL 2 DAY),
(3, 2, 2, (SELECT id FROM house WHERE unit_id = 7 AND floor = 4),
 '2026-10-15', '2027-10-14', 3200.00, 3200.00, 'UNPAID', 'TO_SIGN', NULL,
 '入住申请已通过（社区开启自动通过），待居民承租确认与协议双方确认后生效',
 'NONE', NULL, NULL, NULL, NOW() - INTERVAL 12 DAY, NOW() - INTERVAL 12 DAY),
(4, 1, 1, (SELECT id FROM house WHERE unit_id = 1 AND floor = 2),
 '2023-08-01', '2024-07-31', 3800.00, 3800.00, 'PAID', 'MOVED_OUT', NULL, '租期届满已搬出，押金已原路退还，房屋已恢复挂牌',
 'SIGNED', '2023-07-28 09:10:00', 1, NULL, NOW() - INTERVAL 1100 DAY, NOW() - INTERVAL 780 DAY),
(5, 4, 2, (SELECT id FROM house WHERE unit_id = 8 AND floor = 3),
 '2025-09-01', '2026-08-31', 5500.00, 5500.00, 'UNPAID', 'ACTIVE', NULL,
 '租期已届满但未办理续租或退租，仍为已生效状态（到期门禁 5606 实测样本）',
 'SIGNED', '2025-08-28 14:05:00', 4, NULL, NOW() - INTERVAL 390 DAY, NOW() - INTERVAL 30 DAY),
(6, 5, 1, (SELECT id FROM house WHERE unit_id = 4 AND floor = 2),
 '2026-01-01', '2026-12-31', 3800.00, 3800.00, 'PAID', 'ACTIVE', NULL,
 '押一付一，首期费用已在线支付（首期只收一次 5904 实测样本）',
 'SIGNED', '2025-12-28 11:00:00', 5, NULL, NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 20 DAY);

INSERT INTO lease_agreement (id, lease_id, community_id, template_id, template_name, title, content,
                             template_file_url, template_file_name, status, tenant_id, tenant_confirm_name,
                             tenant_confirm_time, admin_id, admin_confirm_name, admin_confirm_time,
                             cancel_reason, remark, created_by, created_at, updated_at) VALUES
(1, 1, 1, 1, '住宅租赁合同（社区标准模板）', '阳光花园社区 101 室住宅租赁合同',
 '住宅租赁合同\n\n甲方（出租方）：阳光花园社区 物业服务中心\n乙方（承租方）：张伟\n\n一、租赁房屋：阳光花园社区 1 号楼 1 单元 101（房号 101，第 1 层）。\n二、租赁期限：自 2025-07-01 起至 2027-06-30 止。\n三、租金与押金：月租金人民币 2800.00 元；押金人民币 2800.00 元。\n四、双方权利义务：略。\n五、其他约定：本协议经双方在系统内确认后生效。\n\n租约编号：1\n签约日期：2025-06-28',
 NULL, NULL, 'SIGNED', 1, '张伟', '2025-06-28 10:20:00', 2, '陈国强', '2025-06-28 11:05:00',
 NULL, NULL, 2, NOW() - INTERVAL 400 DAY, NOW() - INTERVAL 400 DAY),
(2, 4, 1, 1, '住宅租赁合同（社区标准模板）', '阳光花园社区 201 室住宅租赁合同',
 '住宅租赁合同（历史留档）\n\n甲方：阳光花园社区物业服务中心\n乙方（承租方）：张伟\n房屋：阳光花园社区 1 号楼 1 单元 201\n租期：2023-08-01 至 2024-07-31\n月租金 3800.00 元，押金 3800.00 元。',
 NULL, NULL, 'SIGNED', 1, '张伟', '2023-07-28 09:10:00', 2, '陈国强', '2023-07-28 09:50:00',
 NULL, '租期届满，协议随租约归档', 2, NOW() - INTERVAL 1100 DAY, NOW() - INTERVAL 780 DAY);

INSERT INTO lease_change_log (lease_id, change_type, field_name, field_label, old_value, new_value, reason,
                              operator_id, operator_name, operator_type, created_at) VALUES
(1, 'CREATE', NULL, NULL, NULL, NULL, '居民入住申请审核通过，生成租约', 2, '陈国强', 'ADMIN', NOW() - INTERVAL 400 DAY),
(1, 'AGREEMENT', NULL, NULL, 'NONE', 'SIGNED', '租赁协议双方在线确认完成', 1, '张伟', 'RESIDENT', NOW() - INTERVAL 399 DAY),
(1, 'STATUS', 'status', '租约状态', 'TO_SIGN', 'ACTIVE', '承租确认与协议签署完成，租约生效', NULL, NULL, 'SYSTEM', NOW() - INTERVAL 399 DAY),
(2, 'CREATE', NULL, NULL, NULL, NULL, '入住申请审核通过，生成租约', 2, '陈国强', 'ADMIN', NOW() - INTERVAL 350 DAY),
(2, 'STATUS', 'status', '租约状态', 'TO_SIGN', 'ACTIVE', '承租确认与协议签署完成，租约生效', NULL, NULL, 'SYSTEM', NOW() - INTERVAL 348 DAY),
(3, 'CREATE', NULL, NULL, NULL, NULL, '入住申请自动通过（社区开启自动审批），生成待签约租约', NULL, NULL, 'SYSTEM', NOW() - INTERVAL 12 DAY),
(4, 'CREATE', NULL, NULL, NULL, NULL, '历史租约登记', 2, '陈国强', 'ADMIN', NOW() - INTERVAL 1100 DAY),
(4, 'STATUS', 'status', '租约状态', 'ACTIVE', 'MOVED_OUT', '退租申请审核通过，居民按期搬出', 2, '陈国强', 'ADMIN', NOW() - INTERVAL 780 DAY),
(5, 'CREATE', NULL, NULL, NULL, NULL, '入住申请审核通过，生成待签约租约', 5, '赵敏', 'ADMIN', NOW() - INTERVAL 390 DAY),
(5, 'AGREEMENT', NULL, NULL, 'NONE', 'SIGNED', '租赁协议双方在线确认完成', 4, '陈志强', 'RESIDENT', NOW() - INTERVAL 389 DAY),
(5, 'STATUS', 'status', '租约状态', 'TO_SIGN', 'ACTIVE', '承租确认与协议签署完成，租约生效', NULL, NULL, 'SYSTEM', NOW() - INTERVAL 389 DAY),
(6, 'CREATE', NULL, NULL, NULL, NULL, '入住申请审核通过，生成待签约租约', 2, '陈国强', 'ADMIN', NOW() - INTERVAL 300 DAY),
(6, 'AGREEMENT', NULL, NULL, 'NONE', 'SIGNED', '租赁协议双方在线确认完成', 5, '周雅琴', 'RESIDENT', NOW() - INTERVAL 299 DAY),
(6, 'STATUS', 'status', '租约状态', 'TO_SIGN', 'ACTIVE', '承租确认与协议签署完成，租约生效', NULL, NULL, 'SYSTEM', NOW() - INTERVAL 299 DAY);

/* 首期费用支付单：首期金额 = 首月租金 + 押金，月数与类型固定 FIRST
   · 租约 4（历史已搬出）：7600.00 = 3800 + 3800，2023-08-01 支付
   · 租约 6（在租、未到期）：7600.00 = 3800 + 3800，2026-01-01 支付 → 覆盖「已收首期再发起 → 5904」
   注意：**在租且未支付首期的租约 1（张伟 2800）与租约 2（李思远 7800）刻意不预置支付单**，
   用于实测「发起首期支付」成功路径（200 + 拉起收银台）与幂等续接（DEF-068） */
INSERT INTO lease_payment (payment_no, lease_id, resident_id, payment_type, channel, months, amount, status,
                           channel_trade_no, paid_at, created_at, updated_at) VALUES
('FIRST20230801000218', 4, 1, 'FIRST', 'ALIPAY', 1, 7600.00, 'SUCCESS',
 '2023080122001478650218', '2023-08-01 09:32:11', NOW() - INTERVAL 1100 DAY, NOW() - INTERVAL 1100 DAY),
('FIRST20260101000512', 6, 5, 'FIRST', 'ALIPAY', 1, 7600.00, 'SUCCESS',
 '2026010122001478650512', '2026-01-01 10:22:35', NOW() - INTERVAL 300 DAY, NOW() - INTERVAL 300 DAY);

INSERT INTO lease_move_out_application (id, lease_id, resident_id, community_id, house_id, move_out_date, reason,
                                        status, reviewer_id, review_time, reject_reason, created_at, updated_at) VALUES
(1, 4, 1, 1, (SELECT id FROM house WHERE unit_id = 1 AND floor = 2), '2024-07-31',
 '工作地点变动，租期届满后不再续租', 'APPROVED', 2, NOW() - INTERVAL 780 DAY, NULL,
 NOW() - INTERVAL 800 DAY, NOW() - INTERVAL 780 DAY);

COMMIT;

-- =============================================================================
-- 11. 收尾：整理自增起点（可选 DDL，失败不影响上方数据）
-- =============================================================================
ALTER TABLE community           AUTO_INCREMENT = 4;
ALTER TABLE building            AUTO_INCREMENT = 8;
ALTER TABLE unit                AUTO_INCREMENT = 17;
ALTER TABLE house               AUTO_INCREMENT = 1000;
ALTER TABLE resident            AUTO_INCREMENT = 6;
ALTER TABLE sys_user            AUTO_INCREMENT = 6;
ALTER TABLE service_category    AUTO_INCREMENT = 22;
ALTER TABLE public_resource     AUTO_INCREMENT = 8;
ALTER TABLE housing             AUTO_INCREMENT = 8;
ALTER TABLE notice              AUTO_INCREMENT = 5;
ALTER TABLE work_order          AUTO_INCREMENT = 4;
ALTER TABLE feedback            AUTO_INCREMENT = 2;
ALTER TABLE lease_record        AUTO_INCREMENT = 7;
ALTER TABLE lease_agreement     AUTO_INCREMENT = 3;
ALTER TABLE agreement_template  AUTO_INCREMENT = 3;
ALTER TABLE lease_move_out_application AUTO_INCREMENT = 2;
ALTER TABLE residence_relation  AUTO_INCREMENT = 7;
ALTER TABLE lease_payment       AUTO_INCREMENT = 3;
ALTER TABLE conversation        AUTO_INCREMENT = 1;
/* 后补：E2E/手测会推高这些表的自增起点，一并归零便于下轮手测读到小 id
   （lease_deposit_refund=V22 押金退款、residence_application=V26 关联列所在表、
     viewing_appointment=看房预约；种子自身插入 2/1/1 行，故起点 3/2/2） */
ALTER TABLE lease_deposit_refund AUTO_INCREMENT = 1;
ALTER TABLE residence_application AUTO_INCREMENT = 3;
ALTER TABLE viewing_appointment  AUTO_INCREMENT = 2;

-- =============================================================================
-- 12. 自校验（只读，执行后应看到：3 社区 / 80 户 / 5 居民 / 6 租约 / 租金 2800~7800 / 无 0.1 元）
-- =============================================================================
SELECT '社区' AS 项, COUNT(*) AS 行数 FROM community
UNION ALL SELECT '楼栋', COUNT(*) FROM building
UNION ALL SELECT '单元', COUNT(*) FROM unit
UNION ALL SELECT '房屋', COUNT(*) FROM house
UNION ALL SELECT '房源挂牌', COUNT(*) FROM housing
UNION ALL SELECT '居民', COUNT(*) FROM resident
UNION ALL SELECT '系统用户', COUNT(*) FROM sys_user
UNION ALL SELECT '居住关系', COUNT(*) FROM residence_relation
UNION ALL SELECT '入住申请', COUNT(*) FROM residence_application
UNION ALL SELECT '租约', COUNT(*) FROM lease_record
UNION ALL SELECT '支付单', COUNT(*) FROM lease_payment
UNION ALL SELECT '工单', COUNT(*) FROM work_order
UNION ALL SELECT '公告', COUNT(*) FROM notice
UNION ALL SELECT '反馈', COUNT(*) FROM feedback
UNION ALL SELECT '服务类别', COUNT(*) FROM service_category
UNION ALL SELECT '公共资源', COUNT(*) FROM public_resource;

/* 应全为 0 的"干净"校验项（手测起手前必须为 0，否则说明上一轮残留未清） */
SELECT '会话' AS 项, COUNT(*) AS 行数_应为0 FROM conversation
UNION ALL SELECT '会话消息', COUNT(*) FROM conversation_message
UNION ALL SELECT '通知', COUNT(*) FROM notification
UNION ALL SELECT '通知渠道留痕', COUNT(*) FROM notification_channel_log
UNION ALL SELECT '操作日志', COUNT(*) FROM sys_operation_log
UNION ALL SELECT '押金退款单', COUNT(*) FROM lease_deposit_refund
UNION ALL SELECT '看房沟通消息', COUNT(*) FROM viewing_message
UNION ALL SELECT '不满意跟进', COUNT(*) FROM unsatisfied_followup
UNION ALL SELECT '违约记录', COUNT(*) FROM violation_record
UNION ALL SELECT '拉黑审计', COUNT(*) FROM sys_blacklist_log;

/* 手测锚点（CF-E3-F / CF-E4 起手单）：分两条 SELECT，避免 UNION 的排序规则混用报错 */
SELECT '租约锚点：4=MOVED_OUT+押金PAID、5=ACTIVE已到期(5606样本)、6=ACTIVE首期已付(5904样本)' AS 说明;
SELECT id, status, deposit_status, start_date, end_date FROM lease_record WHERE id IN (4, 5, 6) ORDER BY id;

SELECT '工单锚点：3=WO202609220003 / PENDING / 社区 1 / 居民 1（CF-E4 受理起手单）' AS 说明;
SELECT id, order_no, status, resident_id, community_id FROM work_order WHERE id = 3;

/* V26 强关联自检：APPROVED 的申请必须指向存在的租约（应为 0 行异常） */
SELECT 'APPROVED 申请缺强关联' AS 校验项, COUNT(*) AS 异常行数_应为0
FROM residence_application a WHERE a.status = 'APPROVED' AND a.lease_id IS NULL
UNION ALL SELECT '申请强关联悬空', COUNT(*)
FROM residence_application a LEFT JOIN lease_record l ON l.id = a.lease_id
WHERE a.lease_id IS NOT NULL AND l.id IS NULL
UNION ALL SELECT '看房指向悬空', COUNT(*)
FROM viewing_appointment v LEFT JOIN lease_record l ON l.id = v.lease_id
WHERE v.lease_id IS NOT NULL AND l.id IS NULL;

SELECT MIN(monthly_rent) AS 最小月租, MAX(monthly_rent) AS 最大月租,
       MIN(deposit) AS 最小押金, MAX(deposit) AS 最大押金
FROM (SELECT monthly_rent, deposit FROM housing
      UNION ALL SELECT monthly_rent, deposit FROM lease_record) t;

SELECT COUNT(*) AS 异常低价行数_应为0 FROM (
    SELECT monthly_rent, deposit FROM housing
    UNION ALL SELECT monthly_rent, deposit FROM lease_record) t
WHERE monthly_rent < 800 OR deposit <= 0;

/* -----------------------------------------------------------------------------
   12.1 跨社区一致性自校验（接口预检曾发现「租约 3 属社区 2 而居民无社区 2 居住关系」
        导致 admin1 查询 404；以下每一项都应返回 0 行）
   ----------------------------------------------------------------------------- */
SELECT '房屋社区≠单元社区' AS 校验项, COUNT(*) AS 异常行数_应为0
FROM house h JOIN unit u ON u.id = h.unit_id WHERE h.community_id <> u.community_id
UNION ALL SELECT '单元社区≠楼栋社区', COUNT(*)
FROM unit u JOIN building b ON b.id = u.building_id WHERE u.community_id <> b.community_id
UNION ALL SELECT '居住关系社区≠房屋社区', COUNT(*)
FROM residence_relation r JOIN house h ON h.id = r.house_id WHERE r.community_id <> h.community_id
UNION ALL SELECT '入住申请社区≠房屋社区', COUNT(*)
FROM residence_application a JOIN house h ON h.id = a.house_id WHERE a.community_id <> h.community_id
UNION ALL SELECT '租约社区≠房屋社区', COUNT(*)
FROM lease_record l JOIN house h ON h.id = l.house_id WHERE l.community_id <> h.community_id
UNION ALL SELECT '房源挂牌社区≠房屋社区', COUNT(*)
FROM housing g JOIN house h ON h.id = g.house_id WHERE g.community_id <> h.community_id
UNION ALL SELECT '退租申请社区≠租约社区', COUNT(*)
FROM lease_move_out_application m JOIN lease_record l ON l.id = m.lease_id WHERE m.community_id <> l.community_id
UNION ALL SELECT '生效/待签约租约的居民在该社区无居住关系', COUNT(*)
FROM lease_record l
WHERE l.status IN ('ACTIVE', 'TO_SIGN')
  AND NOT EXISTS (SELECT 1 FROM residence_relation r
                  WHERE r.resident_id = l.tenant_id AND r.community_id = l.community_id)
UNION ALL SELECT '工单类别不属于工单社区', COUNT(*)
FROM work_order w JOIN service_category c ON c.id = w.category_id WHERE c.community_id <> w.community_id
UNION ALL SELECT '工单居民在该社区无居住关系', COUNT(*)
FROM work_order w
WHERE NOT EXISTS (SELECT 1 FROM residence_relation r
                  WHERE r.resident_id = w.resident_id AND r.community_id = w.community_id)
UNION ALL SELECT '反馈居民在该社区无居住关系', COUNT(*)
FROM feedback f
WHERE NOT EXISTS (SELECT 1 FROM residence_relation r
                  WHERE r.resident_id = f.resident_id AND r.community_id = f.community_id)
UNION ALL SELECT '公告发布人未绑定公告社区', COUNT(*)
FROM notice n JOIN notice_target t ON t.notice_id = n.id AND t.target_type = 'COMMUNITY'
WHERE NOT EXISTS (SELECT 1 FROM sys_admin_community ac
                  WHERE ac.admin_id = n.publisher_id AND ac.community_id = t.target_id)
UNION ALL SELECT '公告楼栋定向与发布人社区不符', COUNT(*)
FROM notice n JOIN notice_target t ON t.notice_id = n.id AND t.target_type = 'BUILDING'
JOIN building b ON b.id = t.target_id
WHERE NOT EXISTS (SELECT 1 FROM sys_admin_community ac
                  WHERE ac.admin_id = n.publisher_id AND ac.community_id = b.community_id)
UNION ALL SELECT '资源预约社区≠资源社区', COUNT(*)
FROM resource_reservation rr JOIN public_resource pr ON pr.id = rr.resource_id
WHERE rr.community_id <> pr.community_id
UNION ALL SELECT '资源时段社区≠资源社区', COUNT(*)
FROM resource_timeslot rt JOIN public_resource pr ON pr.id = rt.resource_id
WHERE rt.community_id <> pr.community_id
UNION ALL SELECT '排班社区不在该人员常驻社区内', COUNT(*)
FROM staff_schedule ss
WHERE NOT EXISTS (SELECT 1 FROM staff_community sc
                  WHERE sc.staff_id = ss.staff_id AND sc.community_id = ss.community_id)
UNION ALL SELECT '排班创建人未绑定排班社区', COUNT(*)
FROM staff_schedule ss
WHERE NOT EXISTS (SELECT 1 FROM sys_admin_community ac
                  WHERE ac.admin_id = ss.created_by AND ac.community_id = ss.community_id)
UNION ALL SELECT '排班/常驻社区跨社区的工单派单', COUNT(*)
FROM work_order_assignment wa JOIN work_order w ON w.id = wa.work_order_id
WHERE NOT EXISTS (SELECT 1 FROM staff_community sc
                  WHERE sc.staff_id = wa.assignee_id AND sc.community_id = w.community_id)
UNION ALL SELECT '租约协议社区≠租约社区', COUNT(*)
FROM lease_agreement la JOIN lease_record l ON l.id = la.lease_id WHERE la.community_id <> l.community_id
UNION ALL SELECT '协议模板社区与其默认归属冲突（非 1 或 NULL）', COUNT(*)
FROM agreement_template WHERE community_id IS NOT NULL AND community_id NOT IN (SELECT id FROM community)
UNION ALL SELECT '支付单居民≠租约居民', COUNT(*)
FROM lease_payment p JOIN lease_record l ON l.id = p.lease_id WHERE p.resident_id <> l.tenant_id
UNION ALL SELECT '看房预约社区≠房源社区', COUNT(*)
FROM viewing_appointment va JOIN housing g ON g.id = va.housing_id WHERE va.community_id <> g.community_id;

/* 12.2 首期支付判定分支的样本覆盖（应见：5606 到期样本 1 条、5904 已收首期样本 1 条、200 可付样本 2 条） */
SELECT l.id AS 租约, l.community_id AS 社区, l.status AS 状态, l.end_date AS 止期,
       CASE WHEN l.end_date < CURDATE() THEN '已到期' ELSE '未到期' END AS 到期判定,
       COALESCE((SELECT COUNT(*) FROM lease_payment p
                 WHERE p.lease_id = l.id AND p.payment_type = 'FIRST' AND p.status = 'SUCCESS'), 0) AS 已成功首期单,
       CASE WHEN l.status <> 'ACTIVE' THEN '5602 状态'
            WHEN (SELECT COUNT(*) FROM lease_payment p
                  WHERE p.lease_id = l.id AND p.payment_type = 'FIRST' AND p.status = 'SUCCESS') > 0 THEN '5904 已收首期'
            WHEN l.end_date < CURDATE() THEN '5606 已到期'
            ELSE '200 可发起' END AS 发起首期预期
FROM lease_record l ORDER BY l.id;

-- =============================================================================
-- 脚本结束
-- =============================================================================
