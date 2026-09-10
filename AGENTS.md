# AGENTS.md · 社区居住服务管理系统

> 本文件分两块：文档体系纪律（固定，不可删除或弱化）+ 项目信息（协作填写）。

---

## 一、文档体系纪律（本节不可删除或弱化）

1. **会话开始**：先读 `docs/00_总控.md` + 当前进行中阶段的 `_中控.md`，
   再读本文件项目信息块。未读完不得开始任何任务。
2. **任务合法性**：只做「总控当前指针」所指阶段 `_中控.md` 中记录的任务，
   及其上游包文档推导出的任务。任何新想法 →
   `90_全局/非开发计划内灵感留存处.md`，只记录，不实施，不排序。
3. **冻结保护**：`10_系统定义/系统定义.md` 冻结后不得直接修改；
   需要变更时停下来向用户说明，走决策日志 → 整份重写流程。
   已完成阶段包（✅）的任何后续修改，同样必须先向用户确认。
4. **状态回写**：完成一个任务 → 更新 `_中控.md` 任务状态 + 必要的产物文档；
   完成一个阶段 → 逐条对照判据写验收记录 → 更新总控阶段表和当前指针。
   会话结束前必须完成回写，未回写的会话视为未完成。
5. **决策留痕**：任何技术选型、架构决策、定义变更 →
   `90_全局/决策日志.md` 一行记录（日期/决策/理由/影响的文档）。
6. **状态唯一来源**：除总控与各 `_中控.md` 外，任何文档不得出现阶段状态标记
   （⬜ 未开始 / 🔄 进行中 / ✅ 已完成）。
7. **归档纪律**：旧文档只进 `archive/`，只读不改；新体系文档是唯一权威。
8. **信息来源纪律**：各阶段文档引用外部信息时，必须在对应文档中标识权威来源
   （官方文档/规范/标准/一手资料），不允许引用任意博客信息或不保留来源。
9. **术语纪律**：执行任务接触到术语时，先查 `90_全局/术语表.md`；
   术语表中没有的，停下来询问用户，明确其含义、范围与易歧义处，
   写入术语表后再继续。文档与对话中的术语一律以术语表为准；
   发现同物异名（含代码层命名与文档用语不一致）时先收编进术语表再干活。

## 二、依赖纪律

阶段依赖链（任务只从上游文档推导，跳级引用视为违规）：

```
10 系统定义（锚点·冻结）
   ↓ 细化
20 需求分析 ←────────────────────┐
   ↓ 结构化                   60 评估验收（验收需求实现程度）
30 系统设计                       ↑
   ↓ 按设计实施                测试报告
40 开发实施 ──产出──→ 50 系统测试 ┘
70 部署运行 ← 依赖 40/50/60 产出
```

**依赖向上填补规则**：任何阶段文档的修改（如 20 包新增一条需求），必须能追溯
到上游文档的支撑；若上游没有对应定义，必须先补足上游定义，再在本层完整描述，
不允许只在当前层添加（杜绝悬空需求）。

## 三、项目信息（首次会话与用户协作填写）

- **项目定位**（一句话）：毕业设计课题「基于 Spring Boot 的社区居住服务管理
  系统设计与实现」的配套系统——面向具有明确服务管理方的居住社区的管理者的
  社区居住服务管理系统，统一管理社区房屋、居住关系、日常服务事项
  （工单/公告/反馈）、公共资源预约及运营统计。
- **技术栈与版本约束**（2026-09-06 于 30_系统设计锁定；基线取自已整体
  验证的同体系参考项目 dcim-iot-platform，取代 09-05 调研的 SB 3.x 方案，
  见决策日志）：
  - 后端（`backend/`，Maven + mvnw wrapper）：
    - Java 17（JDK 17）；
    - Spring Boot 4.0.6：webmvc / security / validation / data-redis /
      websocket / flyway（+ flyway-mysql）各 starter；
    - MyBatis-Plus 3.5.16（`mybatis-plus-spring-boot4-starter`，Boot 4 专用，
      非 boot3-starter；另引 `mybatis-plus-jsqlparser` 同版本——3.5.9 起
      jsqlparser 支持拆分为独立构件，分页/数据权限拦截器依赖，
      2026-09-07 开发实施补引，见决策日志）；
    - MySQL：mysql-connector-j 9.6.0（服务端 9.x，connector 兼容 8.x，
      部署环境受限时可降 8.4 LTS 不改代码）；
    - Redis 7；
    - Redisson 4.5.0（分布式锁看门狗机制 + 布隆过滤器，2026-09-06 架构设计
      阶段用户确认引入；原锁定 3.36.0 于 2026-09-07 开发实施阶段升级——
      3.36.0 早于 Boot 4 发布，其自动配置硬引用 Boot 3 的 RedisProperties，
      与 Spring Boot 4.0.6 启动即冲突，4.5.0 为官方针对 Boot 4.0.6 构建
      的版本，见决策日志）；
    - JWT：jjwt 0.12.6（api / impl / jackson）；
    - springdoc-openapi-starter-webmvc-ui 3.0.3（接口契约单一来源，N10）；
    - Druid 1.2.28（`druid-spring-boot-4-starter`，SQL 监控支撑 N1 压测复核）；
    - Lombok；
  - 前端（`frontend/`，npm + Vite + TypeScript；2026-09-07 用户定案引入 TS，
    版本同步更新，见决策日志）：
    - Vue 3.5.42、Vite 8.2.2、@vitejs/plugin-vue 6.0.8；
    - TypeScript 5.9.3 + vue-tsc 3.3.11（构建期 `vue-tsc --noEmit` 类型检查；
      TS 7 新编译器 tsgo 待 vue-tsc 生态跟进，暂不采用）；
    - 组件策略（2026-09-06 用户两次澄清定案）：**自写为主，Element Plus
      可用但不依赖**——面向居民的界面以自写组件保证美观与个性化，
      后台类页面可用 Element Plus（2.14.5）加速但不强制；架构上不强绑定任何
      组件库（组件经过业务薄封装层引用，可整体替换），组件清单
      与归属（自写/EP）在 30 阶段 UI 设计时枚举；
    - Pinia 3.0.4、Vue Router 4.6.4、Axios 1.20.0；
    - ECharts 6 + vue-echarts 8（C9 运营统计，随 C9 模块引入）；
    - Vitest 4（单元测试，服务 50 阶段）；
  - 架构：前后端分离单体应用（便于开发、部署、测试和论文说明）；
    RESTful API；数据库表结构事实来源为 Flyway 迁移脚本（30 阶段产物
    SQL 与迁移脚本合一，避免双份漂移）；
  - 不引入（与参考基线的差异，各有排除理由）：paho-mqtt（无硬件/北向
    接入场景）、EasyExcel（当前无导出需求，出现时再评估进决策日志）；
    Element Plus 降级为"可用不依赖"（非排除项，见前端组件策略条）。
- **代码规范与注释习惯**：
  - 后端遵循《阿里巴巴 Java 开发手册》，前端遵循 Vue 官方风格指南；
  - 注释原则：只写约束与意图，不写"这行做了什么"；
  - **分层注释习惯表**（参考参考项目规范）：

    | 层级 | 注释规范 | 示例 |
    |------|---------|------|
    | Controller | `/** Javadoc */` + `@Tag` + `@Operation` | `/** 工单管理控制器 */`<br>`@Tag(name="工单管理")`<br>`@Operation(summary="创建工单")` |
    | Service | `/* */` 块注释说明业务逻辑 + 接口方法 Javadoc | `/* 工单派单逻辑：检查服务人员绑定社区 */`<br>`/** 派单给指定服务人员 */` |
    | Mapper | `/* */` 块注释说明 SQL 意图 | `/* 按社区ID查询工单，支持状态过滤 */` |
    | Entity | 只用 `@Schema` 注解，禁用 `//` 行注释 | `@Schema(description="工单状态")` |
    | DTO/VO | 只用 `@Schema` 注解，禁用 `//` 行注释 | `@Schema(description="工单创建请求")` |
    | Config | `/** Javadoc */` 说明配置目的与关键参数 | `/** Redis 缓存配置：TTL策略与序列化 */` |

  - **注释强制规则**：
    - Entity/DTO/VO 字段注释必须用 `@Schema(description="...")`，禁用 `//` 行注释
      （理由：springdoc 自动生成接口文档依赖 `@Schema`，`//` 注释不会出现在文档中）；
    - Controller 方法必须用 `@Operation(summary="...")` 说明接口功能；
    - 复杂业务逻辑（Service 层）必须在方法前用 `/* */` 块注释说明判断条件与流程；
    - 禁止无意义注释（如 `// 设置ID`、`// 返回结果`）。

- **目录结构约定**（基于 30_系统设计/架构设计.md §2）：
  - **后端包结构**（`backend/src/main/java/com/community/residence/`，
    根包名以冻结的架构设计.md §2 为准，2026-09-07 用户裁决）：
    ```
    com.community.residence
    ├── config/          # 配置类（Security/Redis/MyBatisPlus/WebSocket/Redisson）
    ├── common/          # 通用类（ApiResponse/BusinessException/错误码/PageVO）
    ├── filter/          # 过滤器（JwtAuthenticationFilter）
    ├── interceptor/     # 拦截器（DataScopeInterceptor 数据级权限）
    ├── schedule/        # 定时任务（租期判定/到期提醒/公告下线/统计回写）
    ├── log/             # AOP 操作留痕切面
    ├── community/       # C1 社区基础信息管理
    ├── resident/        # C2 居民与居住关系管理
    ├── lease/           # C3 租住管理
    ├── workorder/       # C4 服务申请与工单管理
    ├── notice/          # C5 公告广播管理
    ├── feedback/        # C6 居民反馈管理
    ├── reservation/     # C7 公共资源预约管理
    ├── evaluation/      # C8 服务评价管理
    ├── statistics/      # C9 社区运营统计
    ├── auth/            # C10 用户与权限管理
    ├── messaging/       # C11 消息与通知中心
    └── housing/         # C12 房源展示与看房预约
    ```
    每个业务模块包含：`controller/` + `service/` + `mapper/` + `entity/` + `dto/` + `vo/`

  - **前端目录结构**（`frontend/src/`，基于 30_系统设计/UI设计.md §2）：
    ```
    src/
    ├── main.ts
    ├── App.vue
    ├── router/              # 路由配置（按三端分文件）
    │   ├── index.ts
    │   ├── resident.ts      # 居民端路由
    │   ├── guest.ts         # 游客端路由
    │   ├── staff.ts         # 服务人员端路由
    │   └── admin.ts         # 管理端路由
    ├── store/               # Pinia 状态管理
    │   ├── index.ts
    │   ├── user.ts          # 用户状态（登录/角色/权限；setSession/logout
    │   │                     # 联动通知 store 初始化与清理，P2）
    │   ├── notification.ts  # 通知中心（WS 推送去重 + 30s 轮询兜底，P2）
    │   └── permission.ts    # 权限状态（菜单/按钮权限）
    ├── api/                 # API 封装（按 C1~C12 模块分文件；2026-09-07
    │   │                     # P1 前端实施全部完成，约 190 个接口函数；
    │   │                     # P2 增 upload.ts 通用上传）
    │   └── auth.ts          # 认证（居民/管理员登录注册登出）
    ├── views/               # 页面组件（按三端分目录；三端 58 功能视图
    │   │                     # 2026-09-07 P1 全部实现（时点 64，后经
    │   │                     # 2026-09-09 冗余清理删 6 页）；联调复测
    │   │                     # 12/12 通过，另登录注册 2 页 + 403/404）
    │   ├── resident/        # 居民端（16 视图全部实现）
    │   ├── guest/           # 游客端（5 视图全部实现）
    │   ├── staff/           # 服务人员端（4 视图全部实现）
    │   ├── admin/           # 管理端（33 功能视图，按 C1~C12 模块分子目录，
    │   │                     # 其中 auth/ 子目录为 C10 系统用户/操作日志 3 页）
    │   ├── auth/            # 登录注册（LoginView/RegisterView 2 页）
    │   ├── ForbiddenView/NotFoundView  # 全局 403/404
    ├── components/          # 组件（通用组件层与业务组件 P1 已实现）
    │   ├── layout/          # 布局组件（AppHeader/AppSidebar）
    │   ├── business/        # 业务组件（NotificationList 三端复用）
    │   └── common/          # 通用组件（StatusTag/Pagination/SearchBar/
    │                         # FilterPanel/StatCard/Uploader 系列/EChart）
    ├── utils/               # 工具函数（request/auth/permission/responsive/
    │                         # date/websocket——P2 增 WS 连接管理）
    ├── composables/         # 组合式函数（useResponsive，响应式布局设计规范 §5.1）
    ├── directives/          # 自定义指令（v-permission）
    ├── styles/              # 全局样式
    └── types/               # TypeScript 类型定义
    ```

- **启动方式**（2026-09-07 前后端会话回填，随任务完成持续更新）：

  | 环节 | 命令/步骤 | 说明 |
  |------|---------|------|
  | 环境准备 | MySQL 9.6、Redis 7、JDK 17、Node.js 20+ | Vite 8 要求 Node 20.19+ / 22.12+（当前验证于 Node 24） |
  | 环境变量配置 | `export DB_PASSWORD="your_password"` + `export JWT_SECRET="your_secret_key"`（≥32 字符）(Linux/Mac)；Windows 用 `set` | 数据库密码必需；JWT 密钥 dev 未设置时用仅限本机的默认值（生产 profile 拒绝默认密钥，见架构设计 §7）；Redis 可选 `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD`（默认 localhost:6379 无密码） |
  | 数据库初始化 | 建库 `CREATE DATABASE community_residence DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;` | Flyway 随后端启动自动执行 V1（40 张表）+ V2（初始数据）+ V3（超管账号 superadmin / Admin@123456，生产首登必改）+ V6（五角色演示种子：admin1/Admin123456 绑社区、staff1/Staff123456、resident1/Resident123456 已入住 + 游客可见的公告/房源/看房时段 + 健身房资源与时段）+ V7（种子社区默认服务类别目录 7 类，需求 R17），无需手动跑脚本；连接配置在 `backend/src/main/resources/application-dev.yml`（密码读取 `DB_PASSWORD` 环境变量） |
  | 后端启动 | `cd backend && ./mvnw spring-boot:run` | 端口 8080；默认激活 dev profile；接口文档 http://localhost:8080/swagger-ui.html（已放行） |
  | 前端启动 | `cd frontend && npm install && npm run dev` | 开发服务器 http://localhost:5173；`/api`、`/ws` 经 Vite proxy 转发到后端 8080 |
  | 前端构建 | `cd frontend && npm run build` | `vue-tsc --noEmit` 类型检查 + 产物 `dist/`（生产由后端静态托管，架构设计 §7） |
  | 访问入口 | 开发环境 http://localhost:5173 | 路由：游客 `/guest`、登录 `/auth/login`、居民 `/resident`、服务人员 `/staff`、管理端 `/admin`；V6 演示种子账号：超管 `superadmin / Admin@123456`（V3，生产首登必改）、社区管理员 `admin1 / Admin123456`、服务人员 `staff1 / Staff123456`、居民 `resident1 / Resident123456`（已入住）、游客无需账号。**C1~C12 后端业务接口已全量实现（2026-09-07：V1~V7 迁移 40+ 表、六大状态机、功能级+数据级权限；E1/E2/E4/E6/E10b 端到端冒烟通过；2026-09-09 联调复测 12/12 流程全过；文件上传 POST /api/v1/upload（本地磁盘 ./uploads，/uploads/** 静态访问）+ 工单附件（四角色：居民限提交人/服务人员限被派单人）与反馈附件（附件独立管理，已办结禁增删）上传/删除均已实现，BE-ISSUE-9/10 闭环），前端对接以 swagger-ui 与 `接口设计.md` 为准；状态枚举以 Flyway 迁移脚本注释与架构设计 §6 为准（见决策日志 2026-09-07 口径裁决）。P2 后端已交付（2026-09-09）：四定时任务（租期判定 01:00 / 到期提醒 01:30 / 公告下线每小时 / 浏览回写每 5 分钟；Redisson 锁防重入 + sys_task_log 执行日志）+ WebSocket 实时通知（/ws 端点 SockJS + STOMP，CONNECT 帧认证，订阅 /user/queue/notifications；推送失败由 HTTP 轮询兜底）+ 缺陷修复（公告删除 FK 冲突 BE-ISSUE-8、看房可约时段接口 BE-ISSUE-5）；单元测试 113 用例全过。P2 前端（2026-09-09 全部完成）：文件上传链路（工单/反馈附件三端接入）+ 运营看板社区筛选与占用率卡片 + WebSocket 实时通知前端（utils/websocket.ts + store/notification.ts + AppHeader 铃铛，推送/轮询双保障）；综合 E2E 14/14 + typecheck/build 全绿；通知 unread/pull 后端返回纯数组，api 层已适配（见决策日志 2026-09-09）** |

## 四、业务模型快速参考（基于 30_系统设计/数据库设计.md，表名以其 §3 与已执行
的 Flyway 迁移脚本为准：小写无前缀、单数形式，2026-09-07 对齐）

| 模块 | 核心实体表 | 说明 |
|------|----------|------|
| C1 社区基础信息 | `community`、`building`、`unit`、`house`、`public_resource` | 社区→楼栋→单元→房屋四级结构 + 公共资源 |
| C2 居民管理 | `resident`、`residence_application`、`residence_relation` | 居民账号 + 入住申请 + 居住关系 |
| C3 租住管理 | `lease_record`、`lease_reminder` | 租住记录 + 到期提醒去重表 |
| C4 工单管理 | `service_category`、`work_order`、`work_order_process`、`work_order_attachment`、`work_order_assignment` | 服务类别树 + 工单 + 处理记录 + 附件 + 派单关系 |
| C5 公告管理 | `notice`、`notice_target`、`notice_view_record` | 公告 + 目标范围 + 查看记录 |
| C6 反馈管理 | `feedback`、`feedback_message`、`feedback_attachment` | 反馈单 + 会话消息 + 附件 |
| C7 资源预约 | `resource_timeslot`、`resource_reservation`、`violation_record` | 资源时段配置 + 预约记录 + 违约处置 |
| C8 服务评价 | `work_order_evaluation`、`unsatisfied_followup` | 工单评价 + 不满意跟进 |
| C9 运营统计 | `statistics_snapshot`（可选快照） | 运营看板：16卡片+4图表 |
| C10 权限管理 | `sys_user`、`sys_admin_community`、`sys_operation_log`、`auth_token_blacklist`、`sys_blacklist_log` | 系统用户 + 管理员-社区绑定 + 操作日志 + JWT黑名单 + 拉黑审计 |
| C11 通知中心 | `notification`、`notification_channel_log` | 通知推送 + 模拟渠道记录 |
| C12 房源管理 | `housing`、`housing_timeslot`、`viewing_appointment` | 房源 + 看房时段 + 看房预约 |
| 全局配置 | `sys_config` | 全局配置（注册方式开关等） |

**六大状态机**（权威定义见 30_系统设计/架构设计.md §6，状态图见
30_系统设计/assets/02_核心状态机/）：
1. 工单状态（10状态）：待受理→待派单→已派单→已接单→处理中→待确认→已完成 + 已关闭/已驳回/已取消
2. 租住状态（5状态）：待审核→已生效→已搬出→已归档 + 已驳回（「即将到期/已到期」为定时任务日期自动判定标注，非状态流转）
3. 入住申请（3状态）：待审核→已通过 / 已驳回
4. 资源预约（6状态）：待审核→已预约→已完成 + 已拒绝/已取消/已违约
5. 看房预约（5状态）：待确认→已预约→已完成 + 已取消/已违约
6. 反馈状态（3状态）：待受理→会话中→已办结

## 五、禁止项（参考参考项目规范）

| 禁止项 | 理由 |
|--------|------|
| ❌ Entity/DTO/VO 使用 `//` 行注释 | springdoc 只识别 `@Schema` 注解，`//` 不会出现在接口文档中 |
| ❌ Controller 直接操作 Entity | 必须用 DTO 接收请求、VO 返回响应，保证接口稳定性 |
| ❌ Service 直接返回 Entity | 必须转换为 VO 返回，避免敏感字段泄露（如密码哈希） |
| ❌ 绕过统一响应格式 | 所有接口必须返回 `ApiResponse<T>`（含错误场景），业务错误码见 `common/constant/ErrorCode` |
| ❌ 在业务代码中硬编码状态值 | 状态常量统一定义在 `common/constant/` 包下 |
| ❌ 直接使用 `System.out.println()` | 必须使用 Slf4j `@Slf4j` + `log.info/debug/error` |
| ❌ 忽略异常或空 catch 块 | 至少记录日志 `log.error("...", e)`，或转换为业务异常 |
| ❌ SQL 中使用 `SELECT *` | 必须显式列出字段，避免表结构变更后字段映射错误 |
| ❌ 前端直接存储敏感信息（如完整JWT） | JWT 存储 httpOnly cookie 或 sessionStorage（临时会话） |
| ❌ 前端硬编码后端地址 | 必须用环境变量配置（`.env.development` / `.env.production`） |
| ❌ 绕过统一响应格式 | 所有接口必须返回 `ApiResponse<T>`（含错误场景），业务错误码见 `common/constant/ErrorCode` |
| ❌ 跳过参数校验 | Controller 入参必须加 `@Valid` + JSR-303 注解 |
| ❌ 数据级权限依赖业务代码手动过滤 | 必须用 MyBatis-Plus 拦截器统一注入 WHERE 条件 |

## 六、开发会话启动检查清单

新开发会话启动时，必须按顺序完成以下检查：

1. ✅ 读取 `docs/00_总控.md`（确认当前阶段指针）
2. ✅ 读取当前阶段的 `_中控.md`（确认任务清单与验收判据）
3. ✅ 读取本文件（AGENTS.md，了解技术栈与编码规范）
4. ✅ 根据任务类型读取相关设计文档：
   - 后端开发：`30_系统设计/架构设计.md` + `数据库设计.md` + `接口设计.md`
   - 前端开发：`30_系统设计/架构设计.md` + `接口设计.md` + `UI设计.md`
5. ✅ 检查 `docs/40_开发实施/_中控.md` §5.2 模块状态（⬜ = 可开发，✅ = 已完成）
6. ✅ 开始开发前，向用户确认："我准备开发 [模块名称]，依赖模块 [依赖列表] 状态为 [状态]，是否可以开始？"

## 七、开发会话收尾纪律（2026-09-07 增补）

1. **模块完成判定**：一个模块编码完成 **且** 基础测试跑通（单元测试 +
   冒烟可运行）才算完成，缺一不可。
2. **文档回写先行，防信息丢失**：模块完成后 → **立即**更新 README.md
   与 AGENTS.md 中对应的模块状态、启动方式表格、已实现能力等信息
   （`_中控.md` 模块状态照常按纪律 4 回写），然后才允许进入下一步。
3. **Git 提交压轴**：git 提交放在文档回写**完成之后**，代码与文档
   同一次提交；提交信息规范见 `40_开发实施/_中控.md` §6.3。
   （仓库尚未初始化，首次提交前先 `git init`。）
4. **多会话并行时**：对 README.md / AGENTS.md 只做本模块相关的小块
   编辑，禁止整文件重写，避免并行会话互相覆盖。
