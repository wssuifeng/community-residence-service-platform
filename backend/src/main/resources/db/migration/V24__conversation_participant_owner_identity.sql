-- =============================================================================
-- Flyway Migration V24: Conversation Participant Owner Identity (DEF-075)
-- =============================================================================
-- Description: 会话参与者身份空间收口（安全类，与 V21 对 notification 的修法同源）。
--   缺陷：conversation_participant.user_id 是无身份空间列的多态引用
--         （居民＝resident.id、STAFF/ADMIN/SUPER_ADMIN＝sys_user.id），两表自增
--         序列独立且主键可撞号（resident 1 与 sys_user 1 实测撞号）。仅按 user_id
--         判定参与者 ⇒ 撞号的另一身份空间主体可越权读写/订阅该会话
--         （实测：superadmin(sys_user.id=1) 读到 resident1 的群聊，应 403）。
--   修法（与 V21 同一套）：增 owner_type 二元组列 + 复合唯一键替换原 user_id 唯一键，
--         应用层判定 / 展示 / 未读 / WS 订阅全部按 (owner_type, user_id) 收口。
--
-- 表数：**不变**（只加列 + 换索引，不新增表）。
-- =============================================================================

/* 1. 增身份空间列（先允许 NULL，回填后收紧为 NOT NULL） */
ALTER TABLE conversation_participant
    ADD COLUMN owner_type VARCHAR(16) NULL
        COMMENT '参与者身份空间（DEF-075，与 notification.owner_type 同口径）：USER=sys_user 主体（STAFF/ADMIN/SUPER_ADMIN）/ RESIDENT=resident 主体'
        AFTER conversation_id;

-- -----------------------------------------------------------------------------
-- 2. 存量回填：按**调用点语义**判定，不靠"哪张表能查到"猜
-- -----------------------------------------------------------------------------
-- 规则来源（各建群/入群调用点实参语义）：
--   · createViewingGroup(appointmentId, communityId, residentUserId=预约居民 va.user_id, …)
--     → 入群：预约居民(RESIDENT) + 该社区启用 ADMIN(sys_user.id, USER)
--     → ViewingAppointmentService.assign 另以 addParticipant(conv, assigneeId=带看人 sys_user.id) 入群（USER）
--   · createLeaseGroup(leaseId, communityId, residentUserId=租约居民 lease_record.tenant_id, …)
--     → 入群：租约居民(RESIDENT) + 该社区启用 ADMIN(USER)
--   · createWorkOrderGroup(workOrderId, communityId, residentUserId=工单提交人 work_order.resident_id,
--                          intakeAdminId=受理管理员 sys_user.id, …)（R74）
--     → 入群：工单提交人(RESIDENT) + 受理管理员(USER) + 派单后被派服务人员(USER)
--   · openDirect(communityId)：发起居民 = 当前登录居民(resident.id) + 该社区启用 ADMIN(sys_user.id)
--     → DIRECT 无 related_id 回指业务行，按该调用点语义（"居民 ∪ 该社区绑定管理员"）判定：
--       命中 sys_admin_community(community_id, admin_id) 的行 = USER，其余 = RESIDENT

/* 2.1 工单临时群聊（R74）：提交人 = work_order.resident_id */
UPDATE conversation_participant cp
    JOIN conversation c ON c.id = cp.conversation_id AND c.type = 'WORK_ORDER_GROUP'
    JOIN work_order w ON w.id = c.related_id
SET cp.owner_type = CASE WHEN cp.user_id = w.resident_id THEN 'RESIDENT' ELSE 'USER' END;

/* 2.2 租约群聊（R68）：居民 = lease_record.tenant_id */
UPDATE conversation_participant cp
    JOIN conversation c ON c.id = cp.conversation_id AND c.type = 'LEASE_GROUP'
    JOIN lease_record l ON l.id = c.related_id
SET cp.owner_type = CASE WHEN cp.user_id = l.tenant_id THEN 'RESIDENT' ELSE 'USER' END;

/* 2.3 看房群聊（R63）：预约居民 = viewing_appointment.user_id（游客预约 user_id 为空则不建群） */
UPDATE conversation_participant cp
    JOIN conversation c ON c.id = cp.conversation_id AND c.type = 'VIEWING_GROUP'
    JOIN viewing_appointment va ON va.id = c.related_id
SET cp.owner_type = CASE WHEN cp.user_id = va.user_id THEN 'RESIDENT' ELSE 'USER' END;

/* 2.4 居民-管理员直通（DIRECT，无 related_id）：按 openDirect 语义
       "发起居民 ∪ 该会话社区的绑定管理员"——绑定管理员命中者 = USER，其余 = RESIDENT。
       残余风险（已知，非本轮可判）：若某管理员在建群后被解绑、或居民 id 恰与**本社区**
       绑定管理员的 sys_user.id 撞号，则该行按 USER 归类；执行后请核对下方 2.5 自检输出。 */
UPDATE conversation_participant cp
    JOIN conversation c ON c.id = cp.conversation_id AND c.type = 'DIRECT'
SET cp.owner_type = CASE WHEN EXISTS (SELECT 1 FROM sys_admin_community ac
                                      WHERE ac.community_id = c.community_id
                                        AND ac.admin_id = cp.user_id)
                         THEN 'USER' ELSE 'RESIDENT' END;

/* 2.5 未分类兜底自检：本查询必须返回 0 行。非 0 行 = 出现未知会话类型或悬空关联
       （如 conversation.related_id 指向已删除的业务行），**须人工判定，不用默认值猜**；
       因下一步 MODIFY NOT NULL 会在存在 NULL 时失败中止迁移，不会静默带病上线。
       执行后请在迁移日志中确认该行数为 0。 */
-- SELECT cp.id, cp.conversation_id, c.type, c.related_id, cp.user_id
--   FROM conversation_participant cp JOIN conversation c ON c.id = cp.conversation_id
--  WHERE cp.owner_type IS NULL;

/* 3. 唯一键替换：原 uk_conversation_user (conversation_id, user_id) 允许两个身份空间
      在同一会话内各有一行同号 user_id 而互不冲突（撞号者不再互斥、也不再互相冒充）；
      新键 uk_cp_identity (conversation_id, owner_type, user_id) 同时满足
      conversation_participant_ibfk_1 外键对 conversation_id 前导索引的要求。
      原 idx_user_id 单列索引由 (owner_type, user_id) 复合索引取代（"我的会话"查询
      一律按二元组收口）。 */
ALTER TABLE conversation_participant
    DROP INDEX uk_conversation_user,
    DROP INDEX idx_user_id,
    ADD UNIQUE KEY uk_cp_identity (conversation_id, owner_type, user_id),
    ADD KEY idx_cp_owner_user (owner_type, user_id);

/* 4. 收紧非空：存在未回填行时本步失败（宁可迁移中止，也不让撞号行静默落入默认空间） */
ALTER TABLE conversation_participant
    MODIFY COLUMN owner_type VARCHAR(16) NOT NULL
        COMMENT '参与者身份空间（DEF-075，与 notification.owner_type 同口径）：USER=sys_user 主体（STAFF/ADMIN/SUPER_ADMIN）/ RESIDENT=resident 主体';

-- =============================================================================
-- 校验（只读，人工核对）
-- =============================================================================
-- SELECT owner_type, COUNT(*) FROM conversation_participant GROUP BY owner_type;
-- SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'conversation_participant' ORDER BY INDEX_NAME, SEQ_IN_INDEX;
-- SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE();   -- 表数不变
