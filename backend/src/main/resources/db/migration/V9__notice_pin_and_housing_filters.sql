-- =============================================================================
-- V9: D4/D5 前置开发（50 阶段步骤 1c，需求规格 v1.2）
-- ①notice.is_pinned 置顶（R25：置顶公告排列表最前）
-- ②housing.rent_type 租售类型（R53：RENT-出租 / SALE-出售）
-- ③housing.layout 户型冗余列（R53 筛选维度；权威值在 house.layout，
--   房源创建时落值冗余，避免列表筛选 join）
-- =============================================================================

ALTER TABLE notice
    ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0 COMMENT '置顶：0-普通, 1-置顶（列表排最前）' AFTER view_count;

ALTER TABLE housing
    ADD COLUMN rent_type VARCHAR(10) NOT NULL DEFAULT 'RENT' COMMENT '租售类型：RENT-出租, SALE-出售' AFTER deposit,
    ADD COLUMN layout VARCHAR(50) NULL COMMENT '户型（冗余自 house.layout，列表筛选用）' AFTER rent_type;
