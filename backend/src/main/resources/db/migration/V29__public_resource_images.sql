-- =============================================================================
-- Flyway Migration V29: 公共资源展示图片（`public_resource.images`，R75）
-- =============================================================================
-- 背景：公共资源（活动室 / 会议室 / 停车位等）原先没有图片字段，居民端 / 游客端
--       只能用「资源类型 SVG 图标」占位；房源 `housing.images` 早已有图片能力。
--       用户 2026-09-29 裁决：公共资源同样需要展示图片，与 housing.images 完全同口径。
-- 口径（与 housing.images 一致）：
--   库内以逗号分隔字符串存储；接口层 DTO / VO 均为数组（string[]）；
--   无图为空数组（不是 null）；创建 / 更新接收数组，查询 / 详情返回数组。
-- 结构变更（唯一新增迁移，未触碰 V1~V28 的任何一个字节）：
--   public_resource 增列 `images VARCHAR(1000) NULL`（位于 description 之后）
--   —— 仅加一列，不建表、不改其它表、不做存量数据改写 ⇒ 表数不变（仍 53 张业务表）。
-- 兼容性：列可空，存量资源无图保持 NULL，接口层归一为空数组。
-- =============================================================================

ALTER TABLE public_resource ADD COLUMN images VARCHAR(1000) NULL COMMENT '展示图片地址（逗号分隔，与 housing.images 同口径）' AFTER description;

-- =============================================================================
-- 校验（只读，人工核对）：
-- =============================================================================
-- ① 列已存在且类型 / 可空 / 注释正确（应返回一行 images / varchar(1000) / 展示图片地址…）：
-- SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
--   FROM information_schema.COLUMNS
--  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'public_resource' AND COLUMN_NAME = 'images';
-- ② 存量资源无图（本列新增后应无任何非空行）：
-- SELECT id, name, images FROM public_resource WHERE images IS NOT NULL;
