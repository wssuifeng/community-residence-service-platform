-- -----------------------------------------------------------------------------
-- V13: notice_target.target_type 枚举值扩展 'GUEST'（DEF-046 游客公告可见性维度）
-- -----------------------------------------------------------------------------
-- 列为 VARCHAR(20) 且无枚举约束，'GUEST' 值直接可用，无需改表结构或迁移存量数据；
-- 本迁移仅同步列注释，使新枚举值在库内自文档化（权威口径与 NoticeTarget 实体一致）。
-- GUEST 目标行语义：公告显式对游客可见（全平台广播之外的补充口径），
-- target_id 无对应实体，固定落 0；存量无目标公告仍按广播对游客可见（不迁移）。
-- 幂等性：MODIFY 为绝对态重放，重复执行结果一致。

ALTER TABLE notice_target
    MODIFY COLUMN target_type VARCHAR(20) NOT NULL
        COMMENT '目标类型：COMMUNITY-社区, BUILDING-楼栋, GUEST-游客可见（target_id 固定 0）';
