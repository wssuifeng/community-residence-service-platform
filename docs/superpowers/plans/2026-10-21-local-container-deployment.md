# 本地容器化部署与演示环境实施计划

> **For agentic workers:** 本计划在当前会话内内联执行；每个任务完成后进行对应验证并回写70阶段中控。

**Goal:** 建立可在本地直接启动、可向云端迁移的 Docker Compose 部署环境，重置为干净演示数据库，并让本地图片资源只通过 bind mount 保留在用户电脑上。

**Architecture:** 使用前端 Nginx、后端 Spring Boot、MySQL 和 Redis 四个容器。前端镜像只包含 Vite 生产构建产物；后端镜像只包含 JAR；上传目录通过宿主机 `uploads/` bind mount 注入，不进入镜像、不进入 Git，云端部署时可替换为独立持久卷或对象存储。

**Tech Stack:** Docker Compose、Nginx、Node 22、Spring Boot 4.0.6、JDK 17、MySQL 8.4、Redis 7、Flyway。

## Global Constraints

- 60阶段已按用户审阅结论收尾；本轮指针转入70部署运行。
- 手机端当前可用即可，本阶段不追加组件级移动端适配改造。
- 金融专项沿用户确认不执行支付、查单、退款或渠道配置操作。
- 数据库清理仅针对本地开发/测试数据库；执行前保留本地备份，不操作云端或生产数据库。
- 图片资源只保留在本机 `uploads/`，构建上下文和镜像必须排除上传文件。
- 生产/云端凭据只通过环境变量或密钥管理注入，不写入镜像、Compose 文件或 Git。

---

### Task 1: 关闭60阶段并建立70执行入口

**Files:**
- Modify: `docs/00_总控.md`
- Modify: `docs/60_评估验收/_中控.md`
- Modify: `docs/70_部署运行/_中控.md`
- Create: `docs/70_部署运行/部署实施计划.md`
- Create: `docs/70_部署运行/本地容器化部署.md`
- Create: `docs/70_部署运行/演示环境使用说明.md`
- Modify: `docs/90_全局/决策日志.md`

**Steps:**

- [ ] 将60阶段验收记录补为“用户审阅通过，判据逐条达成”，总控指针切换至70。
- [ ] 将70阶段状态改为进行中，登记本地容器化、演示库、图片隔离和云端迁移四组任务。
- [ ] 记录容器架构、图片 bind mount 和演示库重置边界决策。

### Task 2: 增加生产 profile 与容器构建文件

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-prod.yml`
- Create: `docker/backend.Dockerfile`
- Create: `docker/frontend.Dockerfile`
- Create: `docker/nginx.conf`
- Create: `.dockerignore`

**Steps:**

- [ ] 让 `SPRING_PROFILES_ACTIVE` 可覆盖默认 `dev` profile。
- [ ] 为 `prod` profile 使用容器服务名连接 MySQL/Redis，强制要求 `JWT_SECRET`，并通过 `UPLOAD_DIR` 配置上传路径。
- [ ] 用 JDK 17 构建后端、JRE 17 运行后端；用 Node 22 构建前端、Nginx 提供静态文件和 `/api`、`/ws`、`/uploads` 代理。
- [ ] 在 `.dockerignore` 中排除 `uploads/`、`.env`、构建产物、依赖缓存和本地证据目录。

### Task 3: 增加 Compose 本地环境与凭据模板

**Files:**
- Create: `docker-compose.local.yml`
- Create: `.env.docker.example`
- Modify: `.gitignore`
- Modify: `README.md`

**Steps:**

- [ ] 配置 `mysql`、`redis`、`backend`、`frontend` 服务及健康检查、依赖顺序和持久卷。
- [ ] 让后端的 `./uploads` 映射到容器 `/app/uploads`，默认只使用宿主机本地目录。
- [ ] 保证 Compose 不读取或打包根目录 `.env`，使用 `.env.docker` 作为本地容器凭据文件且继续忽略。
- [ ] 补充启动、停止、日志、重建和云端迁移说明。

### Task 4: 增加演示数据库重置脚本

**Files:**
- Create: `tools/demo/reset-demo-database.ps1`
- Create: `tools/demo/reset-demo-database.sql`
- Create: `docs/70_部署运行/演示数据库清理记录模板.md`

**Steps:**

- [ ] 校验目标为本地 `community_residence` 数据库，先用 `mysqldump` 写入 `output/db-backups/`。
- [ ] 删除并重建本地数据库，保留 Flyway 迁移链，由后端启动重新执行 V1~V45 和演示种子。
- [ ] 只清理数据库测试数据，不删除宿主机 `uploads/` 图片资源。
- [ ] 记录账号、迁移版本、图片目录和验证结果。

### Task 5: 本地验证与云端交接

**Files:**
- Modify: `docs/70_部署运行/_中控.md`
- Modify: `docs/70_部署运行/部署实施计划.md`
- Modify: `README.md`

**Steps:**

- [ ] Docker daemon 可用后执行 `docker compose -f docker-compose.local.yml config`、构建和启动。
- [ ] 检查四个服务健康状态、登录入口、API、Redis、Flyway V45、上传目录映射和图片可见性。
- [ ] 执行前端构建、后端测试和最小只读冒烟，不执行金融操作。
- [ ] 将云端部署所需变量、卷、反向代理/TLS和备份要求写入交接文档，不把本地图片复制进镜像或远程仓库。
