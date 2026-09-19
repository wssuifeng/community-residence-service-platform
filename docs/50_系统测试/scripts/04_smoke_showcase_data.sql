-- =============================================================================
-- 04 · 手测展示数据（用户手测 DEF-050 置顶轮播 + DEF-048 多图房源）
-- 目标库：community_residence_test（8081 实例）
-- 前置：Flyway V1~V13 已执行（含 V6 种子：阳光花园社区/102 房屋/时段模板）
-- 幂等：全部 INSERT...WHERE NOT EXISTS，可重复执行
-- =============================================================================

-- 1. 三条置顶广播公告（is_pinned=1，无 notice_target 行 = 全平台广播，游客可见）
INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '欢迎入住阳光花园社区 · 平台使用指南',
       '本指南帮助您快速上手社区服务平台：\n1. 工单报修：提交后管理员将派单处理，全程可追踪；\n2. 公共资源预约：请提前选择时段，同一时段容量有限；\n3. 反馈会话：意见与建议将直达管理方，支持实时沟通。\n如需帮助请联系物业服务中心。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY), 1, 'HIGH', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '欢迎入住阳光花园社区 · 平台使用指南');

INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '社区活动预告 · 周末业主家庭日',
       '本周六上午 9:00 起在中心广场举办业主家庭日：亲子运动会、旧物市集、免费家电维修摊位。\n名额有限，请通过公共资源预约模块报名活动室使用时段。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY), 1, 'NORMAL', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '社区活动预告 · 周末业主家庭日');

INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '安全提示 · 高层住户防火与电动车充电规范',
       '请勿在楼道内停放电动车或入户充电；发现消防通道堵塞请通过反馈渠道上报。\n紧急情况请拨打 119 并通知物业值班电话。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 180 DAY), 1, 'URGENT', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '安全提示 · 高层住户防火与电动车充电规范');

-- 1b. 三条非置顶普通广播公告（is_pinned=0，进游客公告页下方期刊列表；
--     置顶公告全部进轮播、期刊列表仅显示非置顶，故必须另有非置顶数据）
INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '物业服务窗口与报修响应时限说明',
       '物业服务中心工作时间为每日 8:30-17:30；\n报修工单承诺：急修 2 小时内响应、普通维修 24 小时内上门。\n超时未响应可在工单中催办或向管理方反馈。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY), 0, 'NORMAL', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '物业服务窗口与报修响应时限说明');

INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '社区班车时刻调整通知（9 月版）',
       '自本月起社区班车发车时刻调整如下：\n工作日 7:00 / 8:30 / 17:30 / 19:00 四班，周末 9:00 / 16:00 两班。\n乘车点为中心广场东侧站台，请提前 5 分钟候车。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 60 DAY), 0, 'NORMAL', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '社区班车时刻调整通知（9 月版）');

INSERT INTO notice (title, content, publisher_id, status, publish_time, end_time, is_pinned, priority, type)
SELECT '社区图书室开放时间与借阅规则',
       '社区图书室（文化活动中心二层）开放时间：每日 9:00-20:00（周一上午闭馆整理）。\n借阅规则：每证同时借阅 3 册，借期 21 天，可续借一次。\n公共资源预约模块可预约图书室研讨席位。',
       1, 'PUBLISHED', NOW(), DATE_ADD(NOW(), INTERVAL 180 DAY), 0, 'LOW', 'ANNOUNCEMENT'
WHERE NOT EXISTS (SELECT 1 FROM notice WHERE title = '社区图书室开放时间与借阅规则');

-- 2. 多图展示房源（挂 V6 种子：阳光花园 102 房屋；images 五张冒烟展示图）
INSERT INTO housing (community_id, house_id, title, description, monthly_rent, deposit, images, status, view_count, publish_time)
SELECT c.id, h.id, '阳光花园 1 号楼 102 室 · 南向三居（多图实拍）',
       '【手测展示房源】南向三室两厅，全屋新装修，客厅落地窗采光充足；\n配套：地下车位、24 小时安防、周边双地铁。\n详情页含五张实拍图与户型图，支持左右切换与缩略图导航。',
       4500.00, 9000.00,
       '/uploads/smoke_housing_01_exterior.png,/uploads/smoke_housing_02_living.png,/uploads/smoke_housing_03_bedroom.png,/uploads/smoke_housing_04_kitchen.png,/uploads/smoke_housing_05_floorplan.png',
       'AVAILABLE', 0, NOW()
FROM community c
JOIN house h ON h.community_id = c.id AND h.house_number = '102'
WHERE c.name = '阳光花园社区'
  AND NOT EXISTS (SELECT 1 FROM housing WHERE title = '阳光花园 1 号楼 102 室 · 南向三居（多图实拍）');

-- 3. 多图房源的看房时段模板（全周，上午 09:00-12:00 / 下午 14:00-18:00）
INSERT INTO housing_timeslot (housing_id, day_of_week, start_time, end_time, is_available)
SELECT h2.id, d.dow, t.start_time, t.end_time, 1
FROM housing h2
JOIN community c ON c.id = h2.community_id AND c.name = '阳光花园社区'
JOIN (SELECT 1 dow UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7) d
JOIN (SELECT '09:00:00' start_time, '12:00:00' end_time
      UNION SELECT '14:00:00', '18:00:00') t
WHERE h2.title = '阳光花园 1 号楼 102 室 · 南向三居（多图实拍）'
  AND NOT EXISTS (SELECT 1 FROM housing_timeslot ht
                  WHERE ht.housing_id = h2.id AND ht.day_of_week = d.dow
                    AND ht.start_time = t.start_time AND ht.end_time = t.end_time);

-- 验证查询（执行后人工核对）：
-- SELECT id, title, is_pinned FROM notice WHERE is_pinned = 1 AND status = 'PUBLISHED';
-- SELECT id, title, images FROM housing WHERE title LIKE '%多图实拍%';
