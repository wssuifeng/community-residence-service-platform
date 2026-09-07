# 社区居住服务管理系统（community-residence-service-platform）

> 课题名称：**基于 Spring Boot 的社区居住服务管理系统设计与实现**（毕业设计）。
> 对外说明文档。项目进行状态见 `docs/00_总控.md`（本文件不含任何状态信息，
> 这是文档体系的纪律要求）。

## 项目是什么

为具有明确服务管理方的居住社区（商品房小区、长租公寓、公租房、单位/园区宿舍等）
的管理者，提供一套统一管理社区房屋、居民居住关系、服务工单、公告反馈、
公共资源预约与运营统计的 Web 管理系统。详见
`docs/10_系统定义/系统定义.md` 第 1、2 节。

**核心功能（C1~C12）**：社区基础信息管理、居民与居住关系管理、租住管理、服务申请与工单管理、公告广播管理、居民反馈管理、公共资源预约管理、服务评价管理、社区运营统计、用户与权限管理、消息与通知中心、房源展示与看房预约。

**技术架构**：前后端分离单体架构
- **后端**（`backend/`，Maven + mvnw）：Spring Boot 4.0.6 + MyBatis-Plus 3.5.16 + MySQL 8.4+ + Redis 7（Redisson 4.5.0）+ Flyway + springdoc-openapi
- **前端**（`frontend/`，npm + Vite）：Vue 3.5 + Vite 8 + TypeScript + Element Plus 2.14（可用不依赖）

## 怎么跑

**前端**（已可运行；页面当前为路由骨架占位页，具体功能随各业务模块开发）：

```bash
cd frontend
npm install
npm run dev        # 开发服务器 http://localhost:5173（/api、/ws 代理到后端 8080）
npm run build      # vue-tsc 类型检查 + 生产构建（dist/，由后端静态托管）
```

**后端**（已可运行；C1~C12 全部业务模块后端接口已实现）：

```bash
# 前置：本机 MySQL（建库）与 Redis
mysql -uroot -p -e "CREATE DATABASE community_residence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 配置环境变量
export DB_PASSWORD="your_mysql_password"   # MySQL 数据库密码（必需）
export JWT_SECRET="your_jwt_secret_key"    # JWT 签名密钥（≥32 字符；dev 未设置时
                                           # 使用仅限本机的默认值，生产必须设置）
# Windows 用户：
# set DB_PASSWORD=your_mysql_password
# set JWT_SECRET=your_jwt_secret_key

cd backend
./mvnw spring-boot:run   # 端口 8080；Flyway 自动建 40 张表 + 初始数据 + 种子超管
                         # （superadmin / Admin@123456，生产首登必改）；
                         # 接口文档 http://localhost:8080/swagger-ui.html
```

后端已实现能力（2026-09-07）：认证与五角色权限（JWT + 功能级 @PreAuthorize +
数据级权限拦截器）、C1~C12 全部业务接口（社区/房屋四级结构、居民与入住审批联动、
租住状态机、工单全流程状态机、公告发布、反馈会话、资源预约冲突检测、评价与跟进、
运营看板、通知中心、房源与游客看房预约），端到端流程 E1/E2/E4/E6/E10b 已冒烟验证；
单元测试、WebSocket/定时任务/文件上传归入 P2 集成任务（见 40_中控 §5.3/§5.4）。

连接配置在 `backend/src/main/resources/application-dev.yml`（数据库密码从 `DB_PASSWORD` 读取，JWT 密钥从 `JWT_SECRET` 读取）。

### 生成 JWT_SECRET

Linux/Mac：`openssl rand -base64 32`；Windows（PowerShell）：

```powershell
-join ((65..90) + (97..122) + (48..57) | Get-Random -Count 32 | ForEach-Object {[char]$_})
```

生产环境：密钥至少 256 位（32 字节）、强随机生成、定期轮换（轮换需吊销所有旧令牌）。

与 AGENTS.md 项目信息块"启动方式"保持一致。

## 文档体系导航

### 核心文档
- **项目状态与阶段进度**：[`docs/00_总控.md`](docs/00_总控.md)
- **系统定义（目标/非目标）**：[`docs/10_系统定义/系统定义.md`](docs/10_系统定义/系统定义.md)
- **项目信息与技术栈**：[`AGENTS.md`](AGENTS.md)

### 设计文档（已冻结 2026-09-06）
- **架构设计**：[`docs/30_系统设计/架构设计.md`](docs/30_系统设计/架构设计.md)
- **数据库设计**：[`docs/30_系统设计/数据库设计.md`](docs/30_系统设计/数据库设计.md)
- **接口设计**：[`docs/30_系统设计/接口设计.md`](docs/30_系统设计/接口设计.md)
- **UI 设计**：[`docs/30_系统设计/UI设计.md`](docs/30_系统设计/UI设计.md)

### 开发文档
- **开发中控**：[`docs/40_开发实施/_中控.md`](docs/40_开发实施/_中控.md) - 开发任务清单与进度追踪

### 全局文档
- **决策日志**：[`docs/90_全局/决策日志.md`](docs/90_全局/决策日志.md) - 技术选型与架构决策留痕
- **术语表**：[`docs/90_全局/术语表.md`](docs/90_全局/术语表.md) - 项目术语统一定义

## 新会话开发指南

**后端开发会话**：读取 `docs/00_总控.md` + `AGENTS.md` + `docs/30_系统设计/架构设计.md` + `docs/30_系统设计/数据库设计.md` + `docs/30_系统设计/接口设计.md` + `docs/40_开发实施/_中控.md`

**前端开发会话**：读取 `docs/00_总控.md` + `AGENTS.md` + `docs/30_系统设计/架构设计.md` + `docs/30_系统设计/接口设计.md` + `docs/30_系统设计/UI设计.md` + `docs/40_开发实施/_中控.md`

**查看可开发模块**：`docs/40_开发实施/_中控.md` §5.2 模块状态追踪（⬜ = 待开发）

