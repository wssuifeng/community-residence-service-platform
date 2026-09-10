-- =============================================================================
-- 02_bulk_data.sql · 50 系统测试 · 步骤 5 · N7 批量数据（TR-NF-07 / Q5 裁决）
-- =============================================================================
-- 目标规模（01 §4 测量口径）：≥5 社区 / 每社区 ≥1000 户 / 房屋 ≥5000 /
-- 年工单 ≥10000；租约/居住关系/公告/通知按合理比例配套。
--
-- 前提：01_seed_extension.sql 已执行（依赖 perf_resident_001~200 账号池）。
-- 执行（先切到测试库！）：
--   mysql -u root -p community_residence_test < 02_bulk_data.sql
--
-- 实现方式：存储过程 sp_50_bulk_seed + 批内集合式 INSERT ... SELECT（非逐行），
--           分批显式 COMMIT（房屋按社区 1000 行/批、工单 2000 行/批，
--           避免单事务过大）；幂等：以标记社区 TEST-BULK-A社区 存在即整体跳过。
-- 执行耗时预估：本地 SSD 单机 MySQL 约 1~3 分钟（工单 1 万 + 处理记录 1 万为主）。
--
-- !! 重要：本脚本向 notification 表写入带 seq 的记录（MAX(seq)+ROW_NUMBER()）。
--    执行后、启动（或重启）后端前，必须同步 Redis 通知序号，否则后端
--    Redis INCR 生成的 seq 会与库内已存在值撞唯一键：
--      redis-cli SET notification:seq <库内 MAX(seq)>
--      （取值 SQL：SELECT COALESCE(MAX(seq),0) FROM notification;）
--
-- 中途失败处理：本脚本非单事务（分批提交），失败后可能留下部分数据且标记
--    已写入导致重跑跳过——此时按 03_cleanup.sql 的重建口径处理（DROP
--    DATABASE → 00 建库 → 后端 Flyway → 01 → 02），再重跑。
-- =============================================================================

SET SESSION cte_max_recursion_depth = 10010;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_50_bulk_seed $$

CREATE PROCEDURE sp_50_bulk_seed()
proc: BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE v_cid BIGINT UNSIGNED;
    DECLARE v_super BIGINT UNSIGNED;
    DECLARE v_s INT;
    DECLARE v_e INT;
    -- 游标：本脚本创建的 5 个批量社区
    DECLARE cur_community CURSOR FOR
        SELECT id FROM community WHERE name LIKE 'TEST-BULK-%' ORDER BY id;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    -- 幂等标记：批量社区已存在则整体跳过
    IF EXISTS (SELECT 1 FROM community WHERE name = 'TEST-BULK-A社区') THEN
        SELECT 'TEST-BULK 标记社区已存在，本脚本跳过（重造请先走 03 清理口径）' AS notice;
        LEAVE proc;
    END IF;

    SET v_super = (SELECT id FROM sys_user WHERE username = 'superadmin' LIMIT 1);

    -- -------------------------------------------------------------------------
    -- 批 1：5 个批量社区 + 每社区 10 楼栋 × 2 单元（结构层，单事务）
    -- -------------------------------------------------------------------------
    START TRANSACTION;

    INSERT INTO community (name, address, contact_phone, contact_person, description, status)
    SELECT CONCAT('TEST-BULK-', ch.c, '社区'), CONCAT('批量测试市 ', ch.c, ' 路 ', ch.n, ' 号'),
           CONCAT('0571-88', LPAD(ch.n, 6, '0')), '批量测试物业', '50 阶段 N7 批量数据社区', 'ACTIVE'
    FROM (SELECT 'A' AS c, 1 AS n UNION SELECT 'B', 2 UNION SELECT 'C', 3 UNION SELECT 'D', 4 UNION SELECT 'E', 5) ch;

    INSERT INTO building (community_id, name, floors, description)
    SELECT c.id, CONCAT('B', LPAD(nn.n, 2, '0'), ' 号楼'), 10, 'N7 批量楼栋'
    FROM community c
    CROSS JOIN (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10) SELECT n FROM seq) nn
    WHERE c.name LIKE 'TEST-BULK-%';

    INSERT INTO unit (building_id, community_id, name, description)
    SELECT b.id, b.community_id, CONCAT('B', b.id MOD 100, '-U', uu.n), 'N7 批量单元'
    FROM building b
    CROSS JOIN (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2) SELECT n FROM seq) uu
    WHERE b.community_id IN (SELECT id FROM community WHERE name LIKE 'TEST-BULK-%');

    COMMIT;

    -- -------------------------------------------------------------------------
    -- 批 2（游标逐社区）：服务类别 7 条 + 房屋 1000 套/社区（1000 行/批）
    --     房屋规格：每单元 5 层 × 10 房 = 50 套，20 单元 → 1000 套/社区，
    --     5 社区共 5000 套。状态分布：70% OCCUPIED / 20% VACANT / 10% MAINTENANCE。
    -- -------------------------------------------------------------------------
    OPEN cur_community;
    community_loop: LOOP
        FETCH cur_community INTO v_cid;
        IF done THEN
            LEAVE community_loop;
        END IF;

        START TRANSACTION;

        -- 服务类别（沿用 V7 目录，工单 FK 依赖）
        INSERT INTO service_category (community_id, name, description, sort_order, is_active)
        SELECT v_cid, cat.name, cat.description, cat.sort_order, 1
        FROM (
            SELECT '室内维修' AS name, '门窗、灯具、家具等室内设施维修' AS description, 1 AS sort_order
            UNION SELECT '公共设施报修', '社区公共设备设施故障上报', 2
            UNION SELECT '公共区域卫生', '公共区域保洁与垃圾清运', 3
            UNION SELECT '水电管道', '供水供电与管道问题', 4
            UNION SELECT '电梯楼道', '电梯故障与楼道设施问题', 5
            UNION SELECT '安全隐患上报', '消防、治安等安全隐患', 6
            UNION SELECT '其他', '以上类别未覆盖的服务事项', 7
        ) cat
        WHERE NOT EXISTS (SELECT 1 FROM service_category sc WHERE sc.community_id = v_cid AND sc.name = cat.name);

        -- 房屋：每单元 5 层 × 10 房 = 50 套，20 单元 → 1000 套
        INSERT INTO house (unit_id, community_id, house_number, floor, area, room_count, layout, orientation, status, description)
        SELECT u.id, v_cid,
               CONCAT(f.n, LPAD(rm.n, 2, '0')),
               f.n,
               55 + ((f.n * 10 + rm.n) MOD 40) * 1.5,
               1 + ((f.n + rm.n) MOD 3),
               ELT(1 + ((f.n + rm.n) MOD 3), '1室1厅1卫', '2室1厅1卫', '3室2厅2卫'),
               ELT(1 + (rm.n MOD 4), 'SOUTH', 'NORTH', 'EAST', 'WEST'),
               CASE WHEN (f.n * 10 + rm.n) MOD 10 <= 6 THEN 'OCCUPIED'
                    WHEN (f.n * 10 + rm.n) MOD 10 <= 8 THEN 'VACANT'
                    ELSE 'MAINTENANCE' END,
               'N7 批量房屋'
        FROM unit u
        CROSS JOIN (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5) SELECT n FROM seq) f
        CROSS JOIN (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10) SELECT n FROM seq) rm
        WHERE u.community_id = v_cid;

        COMMIT;
    END LOOP;
    CLOSE cur_community;
    SET done = 0;

    -- -------------------------------------------------------------------------
    -- 批 3（游标逐社区）：居住关系 350 条/社区（共 1750）+ 租约 300 条/社区
    --     （共 1500）+ 公告 5 条/社区（共 25）。
    --     租约止期三档（TR-C3-02 / TR-C3-03 数据依赖，N=30 天窗口，
    --     sys_config lease.reminder_days_before_expire=30）：
    --       档 0 已到期：止期 = 今天 - (1~90) 天，状态多为 ACTIVE（到期是查询期
    --                  判定标注而非状态流转，这正是判定用例的验证点）；
    --       档 1 即将到期：止期 = 今天 + (0~29) 天（含止期=当天的边界样本）；
    --       档 2 未到期：止期 = 今天 + (60~359) 天；其中约 2/5 为 MOVED_OUT/
    --                  ARCHIVED 终态样本，其余 ACTIVE。
    -- -------------------------------------------------------------------------
    OPEN cur_community;
    community_loop2: LOOP
        FETCH cur_community INTO v_cid;
        IF done THEN
            LEAVE community_loop2;
        END IF;

        START TRANSACTION;

        -- 居住关系（OCCUPIED 房屋按 id 序前 350 套，居民循环取 200 账号池）
        INSERT INTO residence_relation (resident_id, community_id, house_id, relation_type, move_in_date, is_primary)
        SELECT r.id, v_cid, h.id,
               ELT(1 + (k.n MOD 3), 'OWNER', 'TENANT', 'FAMILY'),
               DATE_SUB(CURDATE(), INTERVAL (100 + k.n MOD 500) DAY),
               IF(k.n MOD 3 = 0, 1, 0)
        FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 350) SELECT n FROM seq) k
        JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn
              FROM house WHERE community_id = v_cid AND status = 'OCCUPIED') h ON h.rn = k.n
        JOIN resident r ON r.username = CONCAT('perf_resident_', LPAD(((k.n * 3 - 1) MOD 200) + 1, 3, '0'));

        -- 租约（本社区房屋按 id 序前 300 套；承租人循环取账号池）
        INSERT INTO lease_record (tenant_id, community_id, house_id, start_date, end_date, monthly_rent, deposit, status, remark)
        SELECT r.id, v_cid, t.house_id,
               DATE_SUB(t.end_date, INTERVAL 365 DAY), t.end_date,
               2500 + (t.k MOD 50) * 100, (2500 + (t.k MOD 50) * 100) * 2,
               CASE WHEN t.tier < 2 THEN 'ACTIVE'
                    WHEN t.k MOD 5 = 0 THEN 'ARCHIVED'
                    WHEN t.k MOD 5 = 1 THEN 'MOVED_OUT'
                    ELSE 'ACTIVE' END,
               CONCAT('N7 批量租约-档', t.tier)
        FROM (
            SELECT k.n AS k, h.id AS house_id, ((k.n - 1) DIV 100) AS tier,
                   CASE WHEN ((k.n - 1) DIV 100) = 0 THEN DATE_SUB(CURDATE(), INTERVAL (1 + k.n MOD 90) DAY)
                        WHEN ((k.n - 1) DIV 100) = 1 THEN DATE_ADD(CURDATE(), INTERVAL (k.n MOD 30) DAY)
                        ELSE DATE_ADD(CURDATE(), INTERVAL (60 + k.n MOD 300) DAY)
                   END AS end_date
            FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 300) SELECT n FROM seq) k
            JOIN (SELECT id, ROW_NUMBER() OVER (ORDER BY id) rn
                  FROM house WHERE community_id = v_cid) h ON h.rn = k.n
        ) t
        JOIN resident r ON r.username = CONCAT('perf_resident_', LPAD(((t.k * 7 - 1) MOD 200) + 1, 3, '0'));

        -- 公告 5 条：3 生效中 + 1 已过期 + 1 已撤回（发布人 = 超管，全系统口径合法）
        INSERT INTO notice (title, content, publisher_id, status, publish_time, start_time, end_time, view_count)
        SELECT CONCAT('TEST-BULK-社区', v_cid, '-公告', i.n),
               CONCAT('N7 批量公告第 ', i.n, ' 条：测试数据，用于规模下公告列表与统计查询。'),
               v_super,
               CASE WHEN i.n <= 3 THEN 'PUBLISHED' WHEN i.n = 4 THEN 'PUBLISHED' ELSE 'WITHDRAWN' END,
               CASE WHEN i.n <= 3 THEN DATE_SUB(NOW(), INTERVAL i.n DAY)
                    WHEN i.n = 4 THEN DATE_SUB(NOW(), INTERVAL 40 DAY)
                    ELSE DATE_SUB(NOW(), INTERVAL 20 DAY) END,
               CASE WHEN i.n <= 3 THEN DATE_SUB(NOW(), INTERVAL i.n DAY)
                    WHEN i.n = 4 THEN DATE_SUB(NOW(), INTERVAL 40 DAY)
                    ELSE DATE_SUB(NOW(), INTERVAL 20 DAY) END,
               CASE WHEN i.n <= 3 THEN DATE_ADD(NOW(), INTERVAL 365 DAY)
                    WHEN i.n = 4 THEN DATE_SUB(NOW(), INTERVAL 10 DAY)
                    ELSE DATE_ADD(NOW(), INTERVAL 30 DAY) END,
               i.n * 37
        FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 5) SELECT n FROM seq) i;

        INSERT INTO notice_target (notice_id, target_type, target_id)
        SELECT n.id, 'COMMUNITY', v_cid
        FROM notice n
        WHERE n.title LIKE CONCAT('TEST-BULK-社区', v_cid, '-公告%')
          AND NOT EXISTS (SELECT 1 FROM notice_target nt
                          WHERE nt.notice_id = n.id AND nt.target_type = 'COMMUNITY' AND nt.target_id = v_cid);

        COMMIT;
    END LOOP;
    CLOSE cur_community;
    SET done = 0;

    -- -------------------------------------------------------------------------
    -- 批 4：工单 10000 条 + 处理记录（SUBMIT 时间线）10000 条 + 评价约 2000 条
    --       分 5 批提交（每批 2000 工单 + 对应处理记录/评价，单事务 ≤ ~4600 行）。
    --       分布口径：
    --         社区：每社区 2000 条（(n-1) DIV 2000 → TEST-BULK-A~E）；
    --         提交人：200 账号池循环（perf_resident_001~200）；
    --         类别：7 类目录循环；
    --         状态：PENDING/TO_ASSIGN/ASSIGNED/ACCEPTED/IN_PROGRESS/TO_CONFIRM/
    --               COMPLETED×2/CLOSED/CANCELLED（10 态全覆盖，COMPLETED 占 20%
    --               供评价与 C9 完成率统计）；
    --         created_at：近一年分布（NOW() - (n MOD 365) 天 + n MOD 1440 分钟，
    --               C9 时间范围筛选与工单量统计的数据基础）；
    --         评价：仅 COMPLETED 工单（n MOD 10 ∈ {6,7}），评分 1~5 均匀
    --               （含 1~2 星不满意样本，C8 满意度统计与 ≤2 星跟进的数据基础）。
    -- -------------------------------------------------------------------------
    SET v_s = 1;
    wo_batch: WHILE v_s <= 10000 DO
        SET v_e = v_s + 1999;

        START TRANSACTION;

        INSERT INTO work_order (order_no, resident_id, community_id, category_id, title, content,
                                contact_phone, address, status, priority, created_at)
        SELECT CONCAT('BULK-WO-', LPAD(i.n, 5, '0')),
               r.id, c.id, sc.id,
               CONCAT('N7 批量工单-', LPAD(i.n, 5, '0')),
               CONCAT('N7 批量工单内容第 ', i.n, ' 条：测试数据，用于规模下工单列表与统计查询。'),
               r.phone,
               CONCAT('TEST-BULK-社区', c.id, ' 批量地址 ', i.n MOD 200, ' 号'),
               ELT(1 + (i.n MOD 10), 'PENDING', 'TO_ASSIGN', 'ASSIGNED', 'ACCEPTED', 'IN_PROGRESS',
                                     'TO_CONFIRM', 'COMPLETED', 'COMPLETED', 'CLOSED', 'CANCELLED'),
               ELT(1 + (i.n MOD 4), 'LOW', 'NORMAL', 'HIGH', 'URGENT'),
               TIMESTAMPADD(MINUTE, i.n MOD 1440, DATE_SUB(NOW(), INTERVAL (i.n MOD 365) DAY))
        FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000)
              SELECT n FROM seq) i
        JOIN community c ON c.name = CONCAT('TEST-BULK-', CHAR(64 + ((i.n - 1) DIV 2000) + 1), '社区')
        JOIN resident r ON r.username = CONCAT('perf_resident_', LPAD(((i.n - 1) MOD 200) + 1, 3, '0'))
        JOIN service_category sc ON sc.community_id = c.id
             AND sc.name = ELT(1 + (i.n MOD 7), '室内维修', '公共设施报修', '公共区域卫生', '水电管道',
                                              '电梯楼道', '安全隐患上报', '其他')
        WHERE i.n BETWEEN v_s AND v_e;

        -- 处理记录：每单一条 SUBMIT 时间线（操作人 = 提交居民，时间 = 工单提交时间）
        INSERT INTO work_order_process (work_order_id, operator_id, operator_type, action,
                                        old_status, new_status, content, created_at)
        SELECT w.id, w.resident_id, 'RESIDENT', 'SUBMIT', NULL, 'PENDING', w.content, w.created_at
        FROM work_order w
        WHERE w.order_no IN (SELECT CONCAT('BULK-WO-', LPAD(i.n, 5, '0'))
                             FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000)
                                   SELECT n FROM seq) i
                             WHERE i.n BETWEEN v_s AND v_e);

        -- 评价：仅 COMPLETED 工单（n MOD 10 ∈ {6,7}），评分 1~5 均匀
        INSERT INTO work_order_evaluation (work_order_id, resident_id, community_id, rating, content, is_satisfied, created_at)
        SELECT w.id, w.resident_id, w.community_id,
               1 + ((CAST(RIGHT(w.order_no, 4) AS UNSIGNED) DIV 10) MOD 5),
               CONCAT('N7 批量评价：', 1 + ((CAST(RIGHT(w.order_no, 4) AS UNSIGNED) DIV 10) MOD 5), ' 星'),
               IF(1 + ((CAST(RIGHT(w.order_no, 4) AS UNSIGNED) DIV 10) MOD 5) >= 3, 1, 0),
               TIMESTAMPADD(DAY, 2, w.created_at)
        FROM work_order w
        WHERE w.order_no IN (SELECT CONCAT('BULK-WO-', LPAD(i.n, 5, '0'))
                             FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000)
                                   SELECT n FROM seq) i
                             WHERE i.n BETWEEN v_s AND v_e AND i.n MOD 10 IN (6, 7));

        COMMIT;

        SET v_s = v_s + 2000;
    END WHILE;

    -- -------------------------------------------------------------------------
    -- 批 5：通知 2000 条（200 账号 × 10 条；已读/未读各半，近 60 天分布）
    --       seq = 库内 MAX(seq) + ROW_NUMBER()；执行后必须同步 Redis 计数器
    --       （见文件头 !! 段落）。
    -- -------------------------------------------------------------------------
    START TRANSACTION;

    INSERT INTO notification (seq, user_id, community_id, title, content, type, source_type,
                              source_id, channels, is_read, created_at)
    SELECT nb.seq_base + ROW_NUMBER() OVER (ORDER BY i.n),
           r.id, NULL,
           CONCAT('N7 批量通知-', LPAD(i.n, 4, '0')),
           CONCAT('N7 批量通知内容第 ', i.n, ' 条：测试数据，用于规模下通知列表与增量拉取。'),
           'SYSTEM', NULL, NULL, 'WEBSOCKET',
           i.n MOD 2,
           TIMESTAMPADD(MINUTE, i.n MOD 1440, DATE_SUB(NOW(), INTERVAL (i.n MOD 60) DAY))
    FROM (WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 2000)
          SELECT n FROM seq) i
    JOIN resident r ON r.username = CONCAT('perf_resident_', LPAD(((i.n - 1) MOD 200) + 1, 3, '0'))
    CROSS JOIN (SELECT COALESCE(MAX(seq), 0) AS seq_base FROM notification) nb;

    COMMIT;

    SELECT 'N7 批量数据写入完成' AS notice;
END proc $$

DELIMITER ;

CALL sp_50_bulk_seed();

DROP PROCEDURE IF EXISTS sp_50_bulk_seed;

-- -----------------------------------------------------------------------------
-- 执行后自检（只读，预期行数与 N7 判据对照）
-- -----------------------------------------------------------------------------
-- SELECT COUNT(*) FROM community WHERE name LIKE 'TEST-BULK-%';   -- 5（≥5 社区 ✓）
-- SELECT COUNT(*) FROM house h JOIN community c ON h.community_id = c.id
--  WHERE c.name LIKE 'TEST-BULK-%';                               -- 5000（≥5000 ✓）
-- SELECT COUNT(*) FROM work_order WHERE order_no LIKE 'BULK-WO-%'; -- 10000（≥10000 ✓）
--   每社区户数：SELECT c.name, COUNT(h.id) FROM community c LEFT JOIN house h
--     ON h.community_id = c.id WHERE c.name LIKE 'TEST-BULK-%' GROUP BY c.name;  -- 各 1000
--   租约三档：SELECT CASE WHEN end_date < CURDATE() THEN 'EXPIRED档'
--     WHEN end_date <= DATE_ADD(CURDATE(), INTERVAL 30 DAY) THEN 'EXPIRING档'
--     ELSE 'FUTURE档' END AS tier, COUNT(*) FROM lease_record l
--     JOIN community c ON l.community_id = c.id WHERE c.name LIKE 'TEST-BULK-%'
--     AND l.status = 'ACTIVE' GROUP BY tier;                      -- 约 500/500/300
--     （另有约 200 条 MOVED_OUT/ARCHIVED 终态租约不计入三档）
--   工单状态分布：SELECT status, COUNT(*) FROM work_order
--     WHERE order_no LIKE 'BULK-WO-%' GROUP BY status;            -- 10 态各约 1000（COMPLETED 2000）
--   评价：SELECT COUNT(*) FROM work_order_evaluation;              -- 约 2000
--   通知 seq 上限（同步 Redis 用）：
--     SELECT COALESCE(MAX(seq), 0) AS max_seq FROM notification;

-- -----------------------------------------------------------------------------
-- 脚本结束
-- -----------------------------------------------------------------------------
