-- =============================================================================
-- V17 · 房屋号同单元唯一（R62 补：批量建房去重，用户反馈 2026-09-20）
-- 口径：房屋号在「单元」内唯一（同单元同层多户由房号本身区分，不允许重复编号）
-- 存量处理（2026-09-20 核查）：unit_id=1 的 101/102/201 各 2 行；重复行存在
--   少量引用（5156 有 1 条 housing、其余引用集中在保留行），故采用「重复行房号
--   追加 -{id} 后缀」重命名，保留全部数据与引用完整性，不做物理删除。
-- =============================================================================

-- 1. 存量重复行重命名（保留 id 最小者为原房号）
UPDATE house h
JOIN (
    SELECT h2.id AS dup_id
    FROM house h2
    JOIN (
        SELECT unit_id, house_number, MIN(id) AS keep_id
        FROM house
        GROUP BY unit_id, house_number
        HAVING COUNT(*) > 1
    ) k ON k.unit_id = h2.unit_id AND k.house_number = h2.house_number
    WHERE h2.id <> k.keep_id
) t ON t.dup_id = h.id
SET h.house_number = CONCAT(h.house_number, '-', h.id);

-- 2. 唯一约束
ALTER TABLE house ADD UNIQUE KEY uk_unit_house_number (unit_id, house_number);
