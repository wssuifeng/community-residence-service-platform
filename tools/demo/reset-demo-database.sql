-- 仅供本机演示数据库重置脚本参考；不要在云端或生产数据库执行。
-- PowerShell 脚本会先生成 output/db-backups/ 备份，再执行等价语句。
DROP DATABASE IF EXISTS community_residence;
CREATE DATABASE community_residence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
