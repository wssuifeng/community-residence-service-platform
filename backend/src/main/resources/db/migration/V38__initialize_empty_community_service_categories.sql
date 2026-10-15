-- Existing empty communities had no selectable categories. Initialize only wholly empty catalogs.
-- Never replace custom catalogs, reactivate inactive categories, or modify work orders.
INSERT INTO service_category (community_id, name, description, sort_order, is_active)
SELECT c.id, cat.name, cat.description, cat.sort_order, 1
FROM community c
CROSS JOIN (
    SELECT '室内维修' AS name, '门窗、灯具、家具等室内设施维修' AS description, 1 AS sort_order
    UNION SELECT '公共设施报修', '社区公共设备设施故障上报', 2
    UNION SELECT '公共区域卫生', '公共区域保洁与垃圾清运', 3
    UNION SELECT '水电管道', '供水供电与管道问题', 4
    UNION SELECT '电梯楼道', '电梯故障与楼道设施问题', 5
    UNION SELECT '安全隐患上报', '消防、治安等安全隐患', 6
    UNION SELECT '其他', '以上类别未覆盖的服务事项', 7
) cat
WHERE NOT EXISTS (
      SELECT 1 FROM service_category sc
      WHERE sc.community_id = c.id
  );
