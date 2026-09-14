-- =============================================================================
-- V12：公告表单字段补全（50 阶段第三批 C3 项，08 §3.6 第 12 项）
-- =============================================================================
-- 管理端公告表单维持提交 priority/type/expireTime（前端 D2 裁决），
-- 后端此前仅接收 endTime/isPinned，契约漂移（DEF-031 关联）：
--   1. notice 增 priority（LOW/NORMAL/HIGH/URGENT，与工单优先级同枚举口径）；
--   2. notice 增 type（当前仅 ANNOUNCEMENT，列宽预留扩展）；
--   3. expireTime 不落新列——语义即 notice.endTime（失效时间），DTO 接收后映射。
-- =============================================================================

ALTER TABLE notice
    ADD COLUMN priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL'
        COMMENT '优先级：LOW-低, NORMAL-普通, HIGH-高, URGENT-紧急（V12）' AFTER is_pinned,
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'ANNOUNCEMENT'
        COMMENT '公告类型：ANNOUNCEMENT-公告（V12）' AFTER priority;
