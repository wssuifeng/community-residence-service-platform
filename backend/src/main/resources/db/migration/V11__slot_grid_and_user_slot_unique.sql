-- =============================================================================
-- V11：C2 可约时段栅格化（Slot Grid）+ 预约防重唯一索引改用户级（50 阶段
-- 第三批 D-端点4 + 第 13 项，08 §3.6/§3.7 设计定案）
-- =============================================================================
-- 模型变更（决策日志 2026-09-14 Slot Grid 定案）：
--   1. public_resource 增 slot_unit 列（预约最小单位，分钟，15/30/60 默认 30）；
--      capacity 语义收窄为「资源级·每时段可承载人数上限」——同槽允许多名居民
--      并发预约至 capacity，与 V10 的「同槽全局唯一」约束不再兼容；
--   2. 撤销 V10 的 uk_reservation_slot（resource_id, reserve_date, start_time,
--      end_time, slot_occupied），替换为用户级防重约束
--      uk_reservation_user_slot（user_id, resource_id, reserve_date, start_time,
--      slot_occupied）——拦截「同一居民同资源同日期同起始时段」的并发重复提交
--      （DEF-002 并发穿透兜底），复用 V10 已建的 slot_occupied 生成列；
--   3. viewing_appointment 的 V10 约束不受本模型影响（C12 看房预约 capacity=1
--      语义简单，不套用 Slot Grid，08 §3.7 注明口径差异）；
--   4. 存量非对齐预约不清洗：占用计数按「覆盖即占用」宽松计入栅格分桶（代码层）；
--   5. 模板 max_bookings 退役为无操作：resource_timeslot 无该列（V1 事实），
--      AvailableSlotVO.maxBookings 字段改按栅格 capacity 口径填充。
-- =============================================================================

-- 1. 清理历史「同居民同资源同日期同起始时段」重复占用（保留最早一条；
--    严格按天拦截时期（DEF-002 修复前）该组合不可能并存，防御性清理，空库为空操作）
UPDATE resource_reservation r
JOIN (
    SELECT r2.id
    FROM resource_reservation r2
    JOIN resource_reservation r3
      ON r3.user_id      = r2.user_id
     AND r3.resource_id  = r2.resource_id
     AND r3.reserve_date = r2.reserve_date
     AND r3.start_time   = r2.start_time
     AND r3.status IN ('PENDING', 'RESERVED')
     AND (r3.created_at < r2.created_at
          OR (r3.created_at = r2.created_at AND r3.id < r2.id))
    WHERE r2.status IN ('PENDING', 'RESERVED')
) dup ON dup.id = r.id
SET r.status = 'CANCELLED',
    r.remark = CONCAT('V11 数据修正：同用户同起始时段重复预约自动取消。原备注：', IFNULL(r.remark, ''));

-- 2. 全局同槽唯一约束降级为用户级（Slot Grid 容量模型：同槽多人至 capacity）
ALTER TABLE resource_reservation
    DROP INDEX uk_reservation_slot;

ALTER TABLE resource_reservation
    ADD UNIQUE INDEX uk_reservation_user_slot
        (user_id, resource_id, reserve_date, start_time, slot_occupied);

-- 3. 资源级栅格粒度（Slot Grid）
ALTER TABLE public_resource
    ADD COLUMN slot_unit INT NOT NULL DEFAULT 30
        COMMENT '预约最小单位（分钟）：15/30/60，默认 30（Slot Grid 栅格粒度，V11）' AFTER capacity;
