-- =============================================================================
-- Flyway Migration V28: OCR 静态评审回填修正（DEF-109 / DEF-120）
-- =============================================================================
-- 背景（2026-09-23 OCR 静态评审发现：V21 / V26 两条已应用迁移的存量回填判据各有一处
--       缺陷；V21~V26 已应用禁改任何字节——2026-09-22 用户裁决，修正经本迁移承载）：
--   ① V21（DEF-067 通知收件人身份空间分离）回填白名单含 LEASE_TENANCY 与
--     LEASE_PAYMENT，判定规则为「source_type 命中白名单 且 user_id 命中 resident.id
--     ⇒ RESIDENT」。缺陷：resident 与 sys_user 两套主键各自自增、**id 可撞号**
--     （DEF-098 同类身份空间撞号）——
--     - LEASE_TENANCY 为**管理员专属来源**（LeaseService.notifyAdminsTenancyConfirmed
--       仅 createForUser 发社区绑定管理员），撞号的管理员通知会被错翻成 RESIDENT，
--       恰是 DEF-067 要消除的跨空间泄漏；
--     - LEASE_PAYMENT / LEASE_MOVE_OUT 为**双空间来源**（居民与管理员各收一条），
--       撞号行的归属无法从存量数据可靠判定。
--     本迁移仅复核**管理员专属来源**的误翻行：source_type='LEASE_TENANCY' 且
--     user_id 实为 sys_user.id 的 RESIDENT 行回滚为 USER——该来源不存在合法
--     RESIDENT 行，命中即误翻（幂等：回滚后不再满足 owner_type='RESIDENT'）。
--     双空间来源的撞号行**不自动回滚**（自动翻回会把真正的居民通知错划为 USER，
--     重新引入 DEF-067），由文末诊断 SQL 人工复核裁决。
--   ② V26（入住申请 → 租约强关联 residence_application.lease_id）存量回填未限定
--     relation_type='TENANT'：运行时审批通过内核仅对 TENANT 申请建租约并回写
--     lease_id（OWNER/FAMILY 只建居住关系，租约由 C3 独立登记），故 OWNER/FAMILY
--     的 APPROVED 申请可能被弱关联回填错挂同屋租约。本迁移先回滚错挂行
--     （非 TENANT 的 lease_id 置 NULL），再按 TENANT 收窄重链——口径同 V26 弱关联
--     回填：同居民/社区/房屋取最新租约 MAX(id)；两步均可幂等重复执行。
-- 表数不变：本迁移只改数据、不改结构。
-- 应用方式：Flyway 随后端启动自动应用（两库由用户执行 flyway migrate 或重启后端，
--           不由代码任务代跑）。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. V21 修正（DEF-109）：管理员专属来源 LEASE_TENANCY 的误翻行回滚为 USER
--    仅动「RESIDENT 但 user_id 实为 sys_user」且「来源为管理员专属」的行；
--    白名单内其余来源（LEASE_PAYMENT / LEASE_MOVE_OUT / LEASE_AGREEMENT /
--    LEASE_SIGN / RESIDENCE_APPLICATION）按调用点会发居民（或双空间），其
--    RESIDENT 行为正确回填，不在本句范围内。
-- -----------------------------------------------------------------------------
UPDATE notification n
   SET n.owner_type = 'USER'
 WHERE n.owner_type = 'RESIDENT'
   AND n.source_type = 'LEASE_TENANCY'
   AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = n.user_id);

-- 诊断（只读，供人工复核，注释形式保留，不随迁移执行）：
-- ①-a 双空间来源的撞号行（归属需按 source_id 反查业务上下文后人工裁决，
--     理论上应为 0 行——V21 时点两库 notification 均为空表，回填实际影响 0 行）：
-- SELECT n.id, n.user_id, n.source_type, n.source_id, n.created_at
--   FROM notification n
--  WHERE n.owner_type = 'RESIDENT'
--    AND n.source_type IN ('LEASE_PAYMENT', 'LEASE_MOVE_OUT', 'LEASE_SIGN')
--    AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = n.user_id)
--  ORDER BY n.id;
-- ①-b 仍存的「RESIDENT 但 user_id 非居民」行（各来源统一兜底，应为 0 行）：
-- SELECT n.id, n.user_id, n.source_type, n.source_id
--   FROM notification n
--  WHERE n.owner_type = 'RESIDENT'
--    AND NOT EXISTS (SELECT 1 FROM resident r WHERE r.id = n.user_id);

-- -----------------------------------------------------------------------------
-- 2. V26 修正（DEF-120）：回滚 OWNER/FAMILY 被错挂的租约强关联
--    residence_application.lease_id 的唯一运行时写入点是审批通过内核，且仅
--    TENANT 分支写（OWNER/FAMILY 申请不建租约）；非 TENANT 行的非空 lease_id
--    只能来自 V26 未收窄的存量回填，一律置 NULL。
-- -----------------------------------------------------------------------------
UPDATE residence_application a
   SET a.lease_id = NULL
 WHERE a.lease_id IS NOT NULL
   AND a.relation_type <> 'TENANT';

-- -----------------------------------------------------------------------------
-- 3. V26 修正（DEF-120）：按 TENANT 收窄重链
--    口径同 V26 弱关联回填：同居民/社区/房屋取最新租约（MAX(id)）；
--    仅链 APPROVED 且 lease_id 仍为空的 TENANT 行（幂等）。
--    写法说明：MySQL 不允许 UPDATE 的子查询直接引用目标表（错误 1093），
--    此处以只读 lease_record 的派生表（GROUP BY 求各组最新租约）参与 JOIN——
--    派生表会被 MySQL 物化，不触发该限制（同 V27 归并段先例）；重链子查询
--    全程不引用 residence_application 自身，无需两步静态 SQL。
-- -----------------------------------------------------------------------------
UPDATE residence_application a
        JOIN (SELECT tenant_id, community_id, house_id, MAX(id) AS max_lease_id
                FROM lease_record
               GROUP BY tenant_id, community_id, house_id) lm
          ON lm.tenant_id = a.resident_id
         AND lm.community_id = a.community_id
         AND lm.house_id = a.house_id
   SET a.lease_id = lm.max_lease_id
 WHERE a.status = 'APPROVED'
   AND a.relation_type = 'TENANT'
   AND a.lease_id IS NULL;

-- =============================================================================
-- 校验（只读，人工核对）
-- =============================================================================
-- ① V21 修正后：LEASE_TENANCY 不应再有 RESIDENT 行（应为 0）：
-- SELECT COUNT(*) FROM notification
--  WHERE source_type = 'LEASE_TENANCY' AND owner_type = 'RESIDENT';
-- ② V26 修正后：非 TENANT 行不应有 lease_id（应为 0）：
-- SELECT id, relation_type, lease_id FROM residence_application
--  WHERE relation_type <> 'TENANT' AND lease_id IS NOT NULL;
-- ③ TENANT + APPROVED 且仍有租约可链的行应全部已链（对照 V26 文末校验 ②）：
-- SELECT a.id, a.resident_id, a.community_id, a.house_id
--   FROM residence_application a
--  WHERE a.status = 'APPROVED' AND a.relation_type = 'TENANT' AND a.lease_id IS NULL;
-- ④ 悬空关联自检（应为 0 行）：
-- SELECT a.id, a.lease_id FROM residence_application a
--   LEFT JOIN lease_record l ON l.id = a.lease_id
--  WHERE a.lease_id IS NOT NULL AND l.id IS NULL;
