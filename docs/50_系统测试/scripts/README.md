# 50 系统测试 · 测试数据脚本（步骤 4 + 步骤 5 产物）

> 配套文档：`../05_测试环境与数据.md`（数据集清单与清理策略的权威口径）。
> 目标库：**community_residence_test**（独立测试库，Q5 裁决）。
> **严禁对开发库 community_residence 执行本目录任何脚本。**

## 脚本清单

| 脚本 | 用途 | 幂等性 |
|------|------|--------|
| `00_create_test_db.sql` | 建独立测试库（utf8mb4 / utf8mb4_unicode_ci）；只建库不建表——40 张业务表 + V1~V8 种子由后端 Flyway 随首次启动自动执行 | 可重复执行（IF NOT EXISTS） |
| `01_seed_extension.sql` | 测试专用种子扩展：TEST-清源里社区（四级结构 + 房屋状态全覆盖 + 2 资源 + 全周时段）、多角色测试账号（test_admin/test_admin2/test_staff/test_staff_disabled/test_resident/test_resident2/test_resident_frozen）、N2 压测 200 账号池（perf_resident_001~200）、冲突并发资源与时段、看房房源与时段、通知/公告锚点（含过期公告与渠道留痕） | 可重复执行（INSERT…WHERE NOT EXISTS，参照 V6 写法） |
| `02_bulk_data.sql` | N7 批量数据（TR-NF-07）：5 社区 TEST-BULK-A~E、每社区 10 楼栋 × 2 单元 × 50 房 = 1000 户（共 5000 房屋）、工单 10000（10 状态全覆盖、近一年时间分布）+ 处理记录 10000 + 评价约 2000、租约 1500（止期三档：已到期/即将到期/未到期，TR-C3-02 数据依赖）、居住关系 1750、公告 25、通知 2000 | 标记跳过（TEST-BULK-A社区 存在即整体跳过；非事务脚本，中途失败须走 03 重建口径） |
| `03_cleanup.sql` | 统一清理（Q5：轮次间累加、收尾统一清理）。推荐口径 A：DROP DATABASE 重建；备选口径 B：TRUNCATE 全部业务表（保留 Flyway 历史，但种子不自动恢复） | DROP 不可逆；执行前必看库名断言 |

## 执行顺序

```
00_create_test_db.sql
        │  （只建库）
        ▼
启动后端（SPRING_DATASOURCE_URL 覆盖指向测试库，见 00 头注；
后端自动跑 Flyway V1~V8：40 张表 + 超管/五角色种子/服务类别/日志留存配置）
        │
        ▼
01_seed_extension.sql   （在 community_residence_test 库执行）
        │
        ▼
02_bulk_data.sql        （按需：仅 N7 规模测试前执行）
        │
        ▼
redis-cli SET notification:seq <库内 MAX(seq)>   （02 执行后必做，防 seq 撞唯一键）
```

命令示例（Windows Git Bash / PowerShell，mysql CLI）：

```bash
mysql -u root -p < 00_create_test_db.sql
# ……启动后端让 Flyway 建表（环境验证步骤执行，本文档不启动服务）……
mysql -u root -p community_residence_test < 01_seed_extension.sql
mysql -u root -p community_residence_test < 02_bulk_data.sql   # 按需
```

## 在哪个库执行

- `00`：连接任何库均可（脚本内不含 USE，CREATE DATABASE 不依赖当前库）；
- `01` / `02`：**必须**显式指定 `community_residence_test`（命令行参数或先 `USE community_residence_test;`）；
- `03`：口径 A（DROP）连接任何库均可；口径 B（TRUNCATE）必须先连测试库。

## 执行限制与验证时点（重要）

- **本 README 编写时点（步骤 4/5）未启动任何后端/前端服务、未执行 01/02
  数据脚本**——Flyway 建表与 01/02 的实际执行验证归**步骤 6 环境验证
  （冒烟前）**，与 05 文档 §2 环境验证记录同口径补记；
- 步骤 4/5 已完成的验证：00 幂等建库执行通过（exit 0）+ 全部脚本静态核对
  （列名逐表对照 V1/V4/V5 迁移）+ 测试库现状只读核查；
- **现状核查发现（2026-09-10）**：community_residence_test 为早期会话遗留脏库
  ——Flyway 仅执行到 **V5**（V6/V7/V8 种子从未在该库执行），且混有 40 阶段
  联调/前端测试遗留数据（9 个历史社区、E2E 账号等）。**因此步骤 6 环境验证
  必须先执行 03 口径 A 重建**（DROP → 00 → 后端 Flyway V1~V8 全新 → 01 → 02），
  再做冒烟；不得在脏库上直接执行 01/02（01 依赖 V6 的阳光花园社区种子）；
- **严禁**对开发库 community_residence 执行本目录脚本；03 的 DROP 口径执行前
  必须核对 `SELECT DATABASE()` 输出。

## 行数验证（执行后自检）

每个脚本尾部附有只读自检 SQL（注释形态），关键判据：

```sql
-- 01 之后：
SELECT COUNT(*) FROM community WHERE name LIKE 'TEST-%';            -- 2（清源里 + …由 02 增至 7）
SELECT COUNT(*) FROM resident WHERE username LIKE 'perf_resident_%'; -- 200（N2 账号池）
SELECT COUNT(*) FROM sys_user WHERE username LIKE 'test_%';          -- 4
SELECT COUNT(*) FROM resource_timeslot rt
  JOIN public_resource r ON rt.resource_id = r.id
 WHERE r.name = 'TEST-并发测试健身房';                                 -- 70（7 天 × 10 档）
SELECT COUNT(*) FROM housing_timeslot;                                -- 清源里房源 63（7 天 × 9 档）

-- 02 之后（N7 判据）：
SELECT COUNT(*) FROM community WHERE name LIKE 'TEST-BULK-%';        -- 5（≥5 社区）
SELECT c.name, COUNT(h.id) FROM community c
  LEFT JOIN house h ON h.community_id = c.id
 WHERE c.name LIKE 'TEST-BULK-%' GROUP BY c.name;                     -- 各 1000 户
SELECT COUNT(*) FROM work_order WHERE order_no LIKE 'BULK-WO-%';     -- 10000（≥10000）
SELECT COUNT(*) FROM lease_record l
  JOIN community c ON l.community_id = c.id
 WHERE c.name LIKE 'TEST-BULK-%';                                     -- 1500（三档止期）
SELECT COALESCE(MAX(seq), 0) FROM notification;                       -- 记下该值同步 Redis
```

## 账号口径（密码哈希复用 V6/V3 已验证 BCrypt 哈希）

| 账号 | 明文密码 | 角色/用途 |
|------|---------|----------|
| superadmin（V3 种子） | Admin@123456 | 超管 |
| admin1 / staff1 / resident1（V6 种子） | Admin123456 / Staff123456 / Resident123456 | 演示三角色 |
| test_admin（→清源里）/ test_admin2（→阳光花园） | Admin123456 | 管理员 + 越权对照 |
| test_staff / test_staff_disabled | Staff123456 | 服务人员（启用/冻结） |
| test_resident / test_resident2 / test_resident_frozen | Resident123456 | 居民（清源里/隔离对照/冻结拒登） |
| perf_resident_001 ~ 200 | Resident123456 | N2 压测账号池 |

## 故障处理

- `02` 中途失败（分批提交非原子）：按 03 口径 A 重建全流程（DROP → 00 →
  Flyway → 01 → 02）后重跑；
- 通知接口报 `uk_seq` 重复：忘记同步 Redis——`redis-cli SET notification:seq
  <库内 MAX(seq)>` 后重启后端；
- 01 重跑未生效：检查是否连错库（`SELECT DATABASE();`）。
