-- =============================================================================
-- V10：预约并发缺陷修复（50 系统测试 DEF-005~008，R33/R55 出口红线）
-- =============================================================================
-- 修复方案「代码层 Redisson 锁 + 数据库唯一约束」双保险：
--   1. 本迁移为 resource_reservation / viewing_appointment 增加占用标记生成列
--      与唯一约束——同资源/房源 + 日期 + 起止时段 + 占用中，数据库层兜底
--      「完全相同时段」的并发重复落库（锁失效或绕过时仍拦截）；
--   2. 半重叠时段（如 10:30-11:00 对 10:00-11:00）无法用唯一约束表达区间重叠，
--      由代码层在分布式锁内做区间重叠判定（锁内单条 INSERT 自动提交后释放锁，
--      保证后到请求可见已落库行）；
--   3. 约束生效前清理并发缺陷期间落库的同槽重复占用数据：每槽保留最早一条，
--      其余置 CANCELLED 并备注来源（新建库无脏数据时为空操作；R33 口径下
--      同槽多占用本身即缺陷产物）。
-- =============================================================================

-- 1. resource_reservation：清理历史同槽重复占用（保留每槽最早一条）
UPDATE resource_reservation r
JOIN (
    SELECT r2.id
    FROM resource_reservation r2
    JOIN resource_reservation r3
      ON r3.resource_id = r2.resource_id
     AND r3.reserve_date = r2.reserve_date
     AND r3.start_time   = r2.start_time
     AND r3.end_time     = r2.end_time
     AND r3.status IN ('PENDING', 'RESERVED')
     AND (r3.created_at < r2.created_at
          OR (r3.created_at = r2.created_at AND r3.id < r2.id))
    WHERE r2.status IN ('PENDING', 'RESERVED')
) dup ON dup.id = r.id
SET r.status = 'CANCELLED',
    r.remark = CONCAT('V10 数据修正：并发缺陷期间同槽重复预约自动取消。原备注：', IFNULL(r.remark, ''));

-- 2. resource_reservation：占用标记生成列 + 同槽唯一约束
ALTER TABLE resource_reservation
    ADD COLUMN slot_occupied TINYINT GENERATED ALWAYS AS
        (IF(status IN ('PENDING', 'RESERVED'), 1, NULL)) STORED
        COMMENT '占用标记：占用中状态（PENDING/RESERVED）为 1，其余为 NULL（同槽唯一约束用，NULL 不参与唯一性）',
    ADD UNIQUE INDEX uk_reservation_slot
        (resource_id, reserve_date, start_time, end_time, slot_occupied);

-- 3. viewing_appointment：清理历史同槽重复占用（保留每槽最早一条）
UPDATE viewing_appointment a
JOIN (
    SELECT a2.id
    FROM viewing_appointment a2
    JOIN viewing_appointment a3
      ON a3.housing_id = a2.housing_id
     AND a3.appointment_date = a2.appointment_date
     AND a3.start_time   = a2.start_time
     AND a3.end_time     = a2.end_time
     AND a3.status IN ('TO_CONFIRM', 'RESERVED')
     AND (a3.created_at < a2.created_at
          OR (a3.created_at = a2.created_at AND a3.id < a2.id))
    WHERE a2.status IN ('TO_CONFIRM', 'RESERVED')
) dup ON dup.id = a.id
SET a.status = 'CANCELLED',
    a.remark = CONCAT('V10 数据修正：并发缺陷期间同槽重复预约自动取消。原备注：', IFNULL(a.remark, ''));

-- 4. viewing_appointment：占用标记生成列 + 同槽唯一约束
ALTER TABLE viewing_appointment
    ADD COLUMN slot_occupied TINYINT GENERATED ALWAYS AS
        (IF(status IN ('TO_CONFIRM', 'RESERVED'), 1, NULL)) STORED
        COMMENT '占用标记：占用中状态（TO_CONFIRM/RESERVED）为 1，其余为 NULL（同槽唯一约束用，NULL 不参与唯一性）',
    ADD UNIQUE INDEX uk_viewing_slot
        (housing_id, appointment_date, start_time, end_time, slot_occupied);
