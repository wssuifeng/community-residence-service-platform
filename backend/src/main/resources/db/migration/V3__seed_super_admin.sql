-- =============================================================================
-- Flyway Migration V3: Seed Super Admin Account
-- =============================================================================
-- Description: 初始超级管理员账号（E10b 权限初始化流程的入口前提：
--              超管登录后创建社区/社区管理员/服务人员账号）
-- Account: superadmin / Admin@123456（密码 BCrypt 强度 10；生产环境首登后必须修改）
-- =============================================================================

INSERT INTO sys_user (username, password_hash, real_name, phone, role, status)
VALUES ('superadmin',
        '$2a$10$N3Jnj4sTBr.GclN0CIX37.6fHWnYs1Hf.1OcKWxSECPm80xwsCKla',
        '超级管理员',
        '13800000000',
        'SUPER_ADMIN',
        'ACTIVE');
