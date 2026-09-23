-- =============================================================================
-- Flyway Migration V26: 入住申请 → 租约 强关联（`residence_application.lease_id`）
-- =============================================================================
-- 背景：申请与租约原先**没有任何显式关联**，前端只能按「同一居民 + 同一社区 + 同一房屋
--       取最新租约」反查（ResidenceApplicationService 原 latestLeaseOf 口径）。
--       同一房屋多份租约（续租 / 重签 / 退租后重租）时该反查会指向**另一份**租约，
--       居民端「查看我的租约」跳错 —— 用户 2026-09-22 裁决：建立明确记录，不再靠猜。
-- 结构变更（唯一新增迁移，未触碰 V22~V25）：
--   residence_application 增 `lease_id BIGINT UNSIGNED NULL`（审批通过内核在
--   创建租约后**同一事务**回写新租约 id）+ 索引 `idx_app_lease(lease_id)`（按租约反查申请）。
-- 存量回填（**尽力回填，不保证精确**）：
--   仅对 `status = 'APPROVED'` 且能按弱关联口径（同一 居民/社区/房屋 的**最新一条租约**）
--   命中的行回填。命中不到的行（例如 OWNER/FAMILY 申请本就无租约、或租约已删）**保持 NULL**，
--   不回填第二遍、不做二次猜测。
--   因此本列的 NULL 语义 =「无关联租约」或「存量无法判定」两类；
--   运行时代码（ApplicationVO）对此**直接返回空**，不再回退弱关联猜测——
--   否则等于把本次裁决要修掉的"错指"重新引入。
-- 幂等：回填条件含 `lease_id IS NULL`，脚本重复执行无副作用（Flyway 亦只执行一次）。
-- 表数不变（仍 54，只加一列一索引）。
-- =============================================================================

ALTER TABLE residence_application
    ADD COLUMN lease_id BIGINT UNSIGNED NULL
        COMMENT '审批通过生成的租约ID（R66/V26 强关联）：由审批通过内核同一事务回写，NULL=无关联租约或存量无法判定'
        AFTER house_id,
    ADD KEY idx_app_lease (lease_id);

-- -----------------------------------------------------------------------------
-- 存量回填（尽力回填，可能不精确）：仅 APPROVED 行，取弱关联口径的最新租约
-- -----------------------------------------------------------------------------
UPDATE residence_application a
SET a.lease_id = (SELECT l.id
                  FROM lease_record l
                  WHERE l.tenant_id = a.resident_id
                    AND l.community_id = a.community_id
                    AND l.house_id = a.house_id
                  ORDER BY l.id DESC
                  LIMIT 1)
WHERE a.status = 'APPROVED'
  AND a.lease_id IS NULL
  AND EXISTS (SELECT 1
              FROM lease_record l2
              WHERE l2.tenant_id = a.resident_id
                AND l2.community_id = a.community_id
                AND l2.house_id = a.house_id);

-- =============================================================================
-- 校验（只读，人工核对）：
-- =============================================================================
-- ① 回填结果（APPROVED 应有值、其余应为 NULL）：
-- SELECT id, status, resident_id, community_id, house_id, lease_id
--   FROM residence_application ORDER BY id;
-- ② 仍未回填的 APPROVED 行（应仅剩"确无租约"的行，需人工确认后按需补录）：
-- SELECT a.id, a.resident_id, a.community_id, a.house_id
--   FROM residence_application a
--  WHERE a.status = 'APPROVED' AND a.lease_id IS NULL;
-- ③ 悬空关联自检（lease_id 指向不存在的租约，应为 0 行）：
-- SELECT a.id, a.lease_id FROM residence_application a
--   LEFT JOIN lease_record l ON l.id = a.lease_id
--  WHERE a.lease_id IS NOT NULL AND l.id IS NULL;
