-- V4: 补充房屋表户型相关字段
-- 修复原因：数据库设计文档要求 layout/orientation 字段，V1 迁移脚本仅有 room_count
-- 影响模块：C1 房屋管理、C12 房源管理（房源展示需要按户型筛选，需求 R53）
-- 创建时间：2026-09-07

ALTER TABLE house
ADD COLUMN layout VARCHAR(50) NULL COMMENT '户型（如"2室1厅1卫"）' AFTER room_count,
ADD COLUMN orientation VARCHAR(20) NULL COMMENT '朝向（如"南"、"东南"）' AFTER layout;
