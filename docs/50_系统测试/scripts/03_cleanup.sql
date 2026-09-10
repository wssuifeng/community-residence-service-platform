-- =============================================================================
-- 03_cleanup.sql · 50 系统测试 · 步骤 5 · 统一清理与重建（Q5 裁决：收尾统一清理）
-- =============================================================================
-- 用途：测试收尾（步骤 11/12）清理测试库数据。推荐口径（二选一）：
--
--   【口径 A · 推荐】DROP DATABASE 重建：
--     彻底、无残留、可复现（重建走 00 → 后端 Flyway → 01 → 02 全流程，
--     恰好复验 NF-09 冷启动口径）。
--     风险：删库不可逆——执行前必须确认连接的是 community_residence_test
--     而不是开发库 community_residence（下方 SELECT 断言即为此设计）。
--
--   【口径 B · 备选】TRUNCATE 全部业务表（保留库与 Flyway schema_history，
--     清空数据后重跑 01/02 种子）：
--     适合「同一轮内快速重置数据、不想重跑 Flyway」的场景。
--     风险：TRUNCATE 隐式提交且绕过 FK 检查需先 SET FOREIGN_KEY_CHECKS=0；
--     flyway_schema_history 保留意味着 V1~V8 不会重放，V6/V7 种子需重跑
--     01 脚本或手动补——**口径 B 下 V2/V3/V6/V7/V8 种子数据不会自动恢复**，
--     必须配合后端 SQL 重放或直接重跑全部种子（不推荐，首选口径 A）。
--
-- 轮次间策略（Q5 裁决）：轮次之间数据【累加】，不执行本脚本；仅测试收尾
-- （或 02 批量数据中途失败需要重造）时使用。
--
-- 执行方式：
--   口径 A：mysql -u root -p < 03_cleanup.sql        （默认 DROP 口径）
--   口径 B：mysql -u root -p --init-command="SET @mode='TRUNCATE'" \
--             community_residence_test < 03_cleanup.sql
--           （或编辑下方 @mode 赋值行后 SOURCE 执行）
-- =============================================================================

-- 执行模式：DROP（默认）或 TRUNCATE——取消注释切换
-- SET @mode = 'TRUNCATE';
SET @mode = 'DROP';

-- -----------------------------------------------------------------------------
-- 安全断言：确认当前连接库为测试库（防误删开发库 community_residence）
-- -----------------------------------------------------------------------------
-- 人工核对（执行前必看）：
SELECT DATABASE() AS current_db,
       @@hostname AS host,
       @@port AS port;

-- -----------------------------------------------------------------------------
-- 口径 A：DROP DATABASE（推荐）
-- -----------------------------------------------------------------------------
DROP DATABASE IF EXISTS community_residence_test;

-- 重建指引（口径 A 执行后按序操作）：
--   1. mysql -u root -p < 00_create_test_db.sql            （建空库）
--   2. 启动后端（SPRING_DATASOURCE_URL 指向测试库，见 00 头注）
--      —— Flyway 自动建 40 张表并执行 V1~V8 种子
--   3. mysql -u root -p community_residence_test < 01_seed_extension.sql
--   4. mysql -u root -p community_residence_test < 02_bulk_data.sql   （按需）
--   5. redis-cli SET notification:seq 2050
--      （重造数据后同步通知序号，取值 = 库内 MAX(seq)）

-- -----------------------------------------------------------------------------
-- 口径 B：TRUNCATE 全部业务表（备选；保留库结构与 Flyway 历史）
-- -----------------------------------------------------------------------------
-- 使用口径 B 时：注释掉上方 DROP 段，取消本段注释，并确认已连接
-- community_residence_test 库（mysql -u root -p community_residence_test）。
--
-- SET FOREIGN_KEY_CHECKS = 0;
-- TRUNCATE TABLE notice_view_record;
-- TRUNCATE TABLE notice_target;
-- TRUNCATE TABLE notice;
-- TRUNCATE TABLE notification_channel_log;
-- TRUNCATE TABLE notification;
-- TRUNCATE TABLE unsatisfied_followup;
-- TRUNCATE TABLE work_order_evaluation;
-- TRUNCATE TABLE work_order_process;
-- TRUNCATE TABLE work_order_attachment;
-- TRUNCATE TABLE work_order_assignment;
-- TRUNCATE TABLE work_order;
-- TRUNCATE TABLE feedback_attachment;
-- TRUNCATE TABLE feedback_message;
-- TRUNCATE TABLE feedback;
-- TRUNCATE TABLE resource_reservation;
-- TRUNCATE TABLE resource_timeslot;
-- TRUNCATE TABLE public_resource;
-- TRUNCATE TABLE violation_record;
-- TRUNCATE TABLE viewing_appointment;
-- TRUNCATE TABLE housing_timeslot;
-- TRUNCATE TABLE housing;
-- TRUNCATE TABLE lease_reminder;
-- TRUNCATE TABLE lease_record;
-- TRUNCATE TABLE residence_relation;
-- TRUNCATE TABLE residence_application;
-- TRUNCATE TABLE house_status_history;
-- TRUNCATE TABLE house;
-- TRUNCATE TABLE unit;
-- TRUNCATE TABLE building;
-- TRUNCATE TABLE service_category;
-- TRUNCATE TABLE statistics_snapshot;
-- TRUNCATE TABLE sys_admin_community;
-- TRUNCATE TABLE resident;
-- TRUNCATE TABLE sys_user;
-- TRUNCATE TABLE sys_operation_log;
-- TRUNCATE TABLE sys_blacklist_log;
-- TRUNCATE TABLE auth_token_blacklist;
-- TRUNCATE TABLE sys_task_log;
-- TRUNCATE TABLE sys_config;
-- SET FOREIGN_KEY_CHECKS = 1;
-- （口径 B 清空后：sys_config/V6/V7/V8 种子不自动恢复，需按 V2/V3/V6/V7/V8
--   迁移内容手动重放或直接改用口径 A——这是推荐口径 A 的原因）

-- -----------------------------------------------------------------------------
-- 清理后验证（只读）
-- -----------------------------------------------------------------------------
-- 口径 A：SHOW DATABASES LIKE 'community_residence_test';   -- 应为空
-- 口径 B（重放种子前）：SELECT COUNT(*) FROM community;      -- 应为 0

-- -----------------------------------------------------------------------------
-- 脚本结束
-- -----------------------------------------------------------------------------
