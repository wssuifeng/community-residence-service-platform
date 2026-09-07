-- =============================================================================
-- Flyway Migration V2: Insert Initial Configuration Data
-- =============================================================================
-- Description: 系统初始化配置数据
-- Author: AI (Claude Code)
-- Date: 2026-09-06
-- Dependencies: V1__create_tables.sql
-- =============================================================================

-- =============================================================================
-- 全局配置初始化（sys_config）
-- =============================================================================

INSERT INTO sys_config (config_key, config_value, description) VALUES
('registration.enabled', 'true', '居民注册开关：true-开放注册, false-关闭注册'),
('registration.need_approval', 'true', '入住申请是否需要审核：true-需审核, false-直接通过'),
('notification.default_channels', 'WEBSOCKET', '默认通知渠道：WEBSOCKET, EMAIL, SMS（逗号分隔）'),
('reservation.max_days_ahead', '30', '资源预约最大提前天数'),
('viewing.max_days_ahead', '14', '看房预约最大提前天数'),
('lease.reminder_days_before_expire', '30', '租期到期提醒提前天数'),
('violation.max_count', '3', '违约最大次数（超过限制账号）'),
('feedback.auto_close_days', '7', '反馈单自动关闭天数（无回复）'),
('notice.default_duration_days', '30', '公告默认有效期天数'),
('system.name', '社区居住服务管理系统', '系统名称');

-- =============================================================================
-- 脚本结束
-- =============================================================================
