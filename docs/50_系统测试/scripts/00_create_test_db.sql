-- =============================================================================
-- 00_create_test_db.sql · 50 系统测试 · 步骤 4/5 · 建独立测试库
-- =============================================================================
-- 目的：创建 50 阶段专用独立测试库 community_residence_test（Q5 裁决）。
--       开发库 community_residence 不做任何写操作，两库完全隔离。
-- 执行方式（MySQL 9.6 本地 3306，管理员连接执行）：
--   mysql -u root -p < 00_create_test_db.sql
--   或在 mysql CLI 内：SOURCE 00_create_test_db.sql;
-- 说明：
--   1. 本脚本只建库 + 授权检查占位，不建业务表——40 张业务表 + V2/V3/V6/V7/V8
--      种子数据由 Flyway 在后端首次启动时自动执行（V1~V8，含超管/五角色演示
--      种子/服务类别/日志留存配置），无需手动跑业务表脚本；
--   2. 后端指向测试库的方式（不改代码，环境变量覆盖 JDBC URL，见
--      05_测试环境与数据.md §1）：
--      Linux/Mac:  export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/community_residence_test?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
--      Windows:    set SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3306/community_residence_test?characterEncoding=UTF-8^&serverTimezone=Asia/Shanghai^&useSSL=false^&allowPublicKeyRetrieval=true
--      （同一方式覆盖 DB_USERNAME/DB_PASSWORD/JWT_SECRET，启动命令不变）
--   3. 建库语句字符集与开发库口径一致（AGENTS.md 启动方式：utf8mb4 + utf8mb4_unicode_ci）。
-- 幂等性：可重复执行（IF NOT EXISTS）。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS community_residence_test
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- 验证（只读）：
--   SHOW DATABASES LIKE 'community_residence_test';
--   USE community_residence_test; SHOW TABLES;  -- 后端 Flyway 启动后应见 40 张表
--   SELECT version();                          -- 确认 MySQL 9.x

-- -----------------------------------------------------------------------------
-- 脚本结束
-- -----------------------------------------------------------------------------
