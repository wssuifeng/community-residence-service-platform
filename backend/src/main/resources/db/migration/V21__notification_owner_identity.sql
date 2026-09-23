-- -----------------------------------------------------------------------------
-- V21: 通知收件人身份空间分离（P0 缺陷 DEF-067；批次 g 实施规格 §二）
-- -----------------------------------------------------------------------------
-- 【根因】系统内存在两套互不相同的身份空间：
--   ① resident 表（居民主体，自带 id/username/password_hash，居民登录主体 = resident.id）；
--   ② sys_user 表（员工/管理员主体，sys_user.id：1=superadmin、2=admin1…）。
--   两表主键各自自增，**id 可撞号**（resident 1 = resident1 与 sys_user 1 = superadmin）。
--   而 notification.user_id 为单列、无外键、注释仅「接收人ID」：写入侧居民调用传
--   resident.id、管理侧调用传 sys_user.id；读取侧 NotificationService.page/unread/pull
--   一律 eq(user_id, 当前登录主体 id) ⇒ **撞号即双向串收**（实测：notification id=17
--   user_id=1 属居民口径「首期费用支付成功」，却出现在 superadmin 的通知中心）。
-- 【修复】增列 owner_type 标记该行的收件人身份空间，与 user_id 共同构成
--   (owner_type, user_id) 唯一收件人身份；读取/已读/增量拉取/WebSocket 推送全部按该
--   二元组收口（服务层改造见 NotificationService，调用点标注见批次 g 实施报告）。
-- 【表数】不新建表（业务表仍为 52）：本迁移仅加列 + 加索引 + 存量回填。
--
-- -----------------------------------------------------------------------------
-- 【存量回填判定依据】（写入侧调用点语义反推，规格 §2.1）
-- -----------------------------------------------------------------------------
-- 调用点语义：居民侧调用传 resident.id，管理侧调用传 sys_user.id（boundAdminIds /
--   assigneeId / handlerId / 超管账号）。source_type 由调用点给出，其中下列来源
--   按调用点实现**只可能**投递给居民主体（居民专属租约/入住闭环）：
--     LEASE_PAYMENT（PaymentService 支付成功）、LEASE_MOVE_OUT（退租申请审核结果）、
--     LEASE_AGREEMENT（AgreementService 协议发起/撤回/确认）、LEASE_TENANCY（承租确认）、
--     LEASE_SIGN（签约）、RESIDENCE_APPLICATION（入住申请审核结果）。
--   故判定规则 = source_type 命中上述白名单 **且** user_id 命中 resident.id ⇒ RESIDENT；
--   其余一律保持 USER（列默认值），管理侧投递口径不变（管理侧逻辑本身正确，勿改）。
--   ⚠ 残留边界（已知且已在报告中声明）：白名单外的来源类型（NOTICE / WORK_ORDER /
--   FEEDBACK / RESOURCE_RESERVATION / VIEWING_APPOINTMENT / LEASE_RECORD / LEASE /
--   TASK）为**双身份空间共用**来源（同一 source_type 既可能投居民也可能投管理员），
--   无法从存量数据可靠判定，按规格保持 USER；此类残留行在新写入路径上已全部带
--   owner_type，不受影响。运维可按注释末的「诊断 SQL」人工复核存量可疑行。
--
-- -----------------------------------------------------------------------------
-- 【回填前统计留痕】（2026-09-21 于开发库 community_residence 实测）
-- -----------------------------------------------------------------------------
--   notification 总行数 = 0；各 owner_type 行数 = USER 0 / RESIDENT 0；
--   各 source_type 行数：无数据（表为空，无 source_type 分组行）。
--   ⇒ 本次回填实际影响 0 行；判据与脚本保留，任何已产生通知数据的环境按同一判据回填。
--   统计语句（迁移执行前手工运行并留档，见实施报告 §1）：
--     SELECT COUNT(*) AS total FROM notification;
--     SELECT source_type, COUNT(*) AS cnt,
--            SUM(user_id IN (SELECT id FROM resident)) AS hit_resident
--       FROM notification GROUP BY source_type ORDER BY cnt DESC;
--
-- -----------------------------------------------------------------------------
-- 【备份与回滚】不建物理备份表（避免把运维快照计入业务表清单，规格 §五 要求表数不变）；
--   改用「确定性判据 + 可复现快照 SQL + 逐类统计留痕」：
--   · 回填前如需物理快照，先手工执行（表名由运维自定，用完自行处置）：
--       CREATE TABLE notification_v21_backup AS
--       SELECT id, user_id, source_type, source_id, created_at FROM notification
--        WHERE source_type IN ('LEASE_PAYMENT','LEASE_MOVE_OUT','LEASE_AGREEMENT',
--                              'LEASE_TENANCY','LEASE_SIGN','RESIDENCE_APPLICATION')
--          AND user_id IN (SELECT id FROM resident);
--   · 回滚语句（同一确定性命中谓词，可幂等反向置回 USER）：
--       UPDATE notification SET owner_type = 'USER'
--        WHERE source_type IN ('LEASE_PAYMENT','LEASE_MOVE_OUT','LEASE_AGREEMENT',
--                              'LEASE_TENANCY','LEASE_SIGN','RESIDENCE_APPLICATION')
--          AND user_id IN (SELECT id FROM resident);
--   · 诊断 SQL（白名单外但 user_id 命中 resident.id 的存量可疑行，人工复核用）：
--       SELECT n.id, n.user_id, n.source_type, n.source_id FROM notification n
--        WHERE n.source_type NOT IN ('LEASE_PAYMENT','LEASE_MOVE_OUT','LEASE_AGREEMENT',
--                                    'LEASE_TENANCY','LEASE_SIGN','RESIDENCE_APPLICATION')
--          AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id = n.user_id)
--          AND EXISTS (SELECT 1 FROM resident r WHERE r.id = n.user_id);
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 1. notification：增列 owner_type（与 user_id 组成唯一收件人身份，P0 串号修复）
--    默认值 'USER' 仅为存量列回填服务；应用层**禁止**依赖该默认值隐式落库
--    （NotificationService 只提供 createForUser / createForResident 两个显式入口）。
-- -----------------------------------------------------------------------------
ALTER TABLE notification
    ADD COLUMN owner_type VARCHAR(16) NOT NULL DEFAULT 'USER'
        COMMENT '通知归属主体类型：USER-sys_user主体（员工/管理员）/ RESIDENT-resident主体；与 user_id 组成唯一收件人身份（P0 串号修复 DEF-067）'
        AFTER user_id;

-- -----------------------------------------------------------------------------
-- 2. 组合索引：列表分页 / 未读 / 增量拉取一律按 (owner_type, user_id, seq) 收口
-- -----------------------------------------------------------------------------
ALTER TABLE notification
    ADD INDEX idx_notification_owner (owner_type, user_id, seq);

-- -----------------------------------------------------------------------------
-- 3. 存量回填：居民专属来源且 user_id 命中 resident.id 的行置 RESIDENT（判据见文件头）
-- -----------------------------------------------------------------------------
UPDATE notification
   SET owner_type = 'RESIDENT'
 WHERE source_type IN ('LEASE_PAYMENT', 'LEASE_MOVE_OUT', 'LEASE_AGREEMENT',
                       'LEASE_TENANCY', 'LEASE_SIGN', 'RESIDENCE_APPLICATION')
   AND user_id IN (SELECT id FROM resident);
