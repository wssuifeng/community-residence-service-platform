# 验收修复第二轮 实现计划（居民端 5 项 + 管理端 6 项 + 全局表单原则）

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development
> 逐任务实现此计划（沿用第一轮既定模式）。步骤使用复选框（`- [ ]`）跟踪进度。
> 账本沿用 `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/progress.md`，条目用 `R2-<N>` 前缀。

**目标：** 修复用户验收发现的 11 个问题（居民端预约重复/预约体验重构/通知三修，管理端看板两处/导航对齐/预约管理改版/社区结构增强），并把「少用大表单」确立为全站设计约定。

**架构：** 全部为前端修复与重构，后端零改动；接口能力缺口按既定纪律记入 BEAUTIFY_NOTES「后端适配清单」而非前端造假。新增通知跳转映射抽为共享工具（消息中心与铃铛下拉共用）；居民端预约改为「资源列表 → 资源详情 → 预约子页（日历选日期 + 时段下拉）」三段流；管理端预约改为「先选资源 + 月历视图 + 点日进日详情」。

**技术栈：** Vue 3.5 + TS + Element Plus（可用不依赖）+ 自写组件 + variables.css token；验证 = `npm run build` + Playwright 截图与交互回归（脚本仅本地）。

---

## 执行状态（2026-09-12，8 任务 R1~A8 全部闭环）

> 各行结论以账本（`.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/progress.md` R2-* 条目）
> 与各 round2-task-N-report.md 为准；落档明细见 BEAUTIFY_NOTES.md。

| 任务 | 状态 | 一句话结论 |
|------|------|-----------|
| R1 | ✅ 完成（审查一次通过） | 通知三修：共享跳转映射 notificationNav + markAll/unread 响应适配 + 铃铛面板可点；sourceType/sourceId 后端齐全无缺口；1 Minor 延后（handleMarkAll 后沿用当前页号） |
| R2 | ✅ 完成 | **定性结论：重复 = 历轮验收残留的 26 条真实库行**（id 全唯一，排除后端 join 扩散/双击/模板渲染三假设），已授权清理并逐行留证（保留 id=1 demo 行）；列表按 id 去重兜底落地 |
| R3 | ✅ 完成（审查一次通过） | 居民预约三段流落地；重大发现：旧创建请求体与后端 DTO 零交集（重构前必 400），已按真实契约适配留证；后端顺序查重实测 5002 有效，并发唯一约束缺口记后端适配清单 |
| A4 | ✅ 完成（1 轮修复闭环） | 入住率 6 处 + 动态三源逐项跳转 + 公告不显示根因（top-6 时间窗口竞争）修为每类型保底；保底驱逐缺陷（Important）经 1 轮修复 + 注入回归 18/18 |
| A5 | ✅ 完成（controller 核验） | 根因修正 = 头部 1200px 限宽与管理端全宽错位（非缺 margin-left:auto）；管理端放开限宽集群贴视口右缘；2 条留用户裁决（Logo 形态 / 居民端贴列非贴视口） |
| A6 | ✅ 完成（审查一次通过） | 预约管理改「资源先行 + 月历 + 日详情」三段；`?week=` 自动迁移 `?date=`；五动作与二次确认文案逐字保留；月数据实测 6.2KB 免懒加载 |
| A7 | ✅ 完成（审查一次通过） | 批量建楼/单元/房屋（前端循环单建，端点缺口记清单）+ 房屋行内编辑/多选删除 + 住户抽屉（登记住户降级为审批流引导）；测试数据自清理留证 |
| A8 | ✅ 完成（纯文档） | 设计约定落档 + 后端适配清单 R2 轮条目 + 残留数据更新 + 进度快照 R2 轮记录 + 本执行状态表 |

---

## 全局约定（每个任务遵守，与第一轮一致）

1. **不做任何 git 操作**（D3 延续，全部改动留工作树）
2. token 只增量不改既有值（先 grep）；组件禁止硬编码 hex；配色按 BEAUTIFY_NOTES「配色纪律」（画布冷灰已二次裁决）
3. **接口能力缺口 → BEAUTIFY_NOTES「后端适配清单」追加一条，前端不做假控件/假数据**；MOCK 占位标注 `【MOCK 临时占位区块】`
4. **大表单治理原则（本轮新裁决，落档 BEAUTIFY_NOTES「设计约定」）**：能分步不分屏、能抽屉不弹窗、能对话框不整页；单表单超过一屏或超过 6 个输入组必须拆分步/分区/抽屉；本轮新增页面全部遵守，既有页面本轮不动（用户后续逐页裁决）
5. 居民端文件本轮经用户点名授权修改（此前并行会话领地，本轮按用户指令进入）
6. 每任务：保留清单先行（数据绑定/动作/二次确认逐项存活）、build 全绿、Playwright 验证、报告写 `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/round2-task-N-report.md`

## 根因调研结论（已核实到行号，实现者直接使用）

| # | 问题 | 根因（已核实） |
|---|------|----------------|
| 1 | 消息中心「全部已读」报错 `Cannot read properties of null (reading 'count')` | `api/notification.ts:56` `markAllNotificationsRead()` 未适配响应——后端 data 为 null 时 `result.count` 抛错（NotificationList `handleMarkAll`）；同文件 `listUnreadNotifications` 亦有同类风险（后端返回裸数组，`unread.count` 静默失败致轮询角标不更新） |
| 2 | 消息中心反馈通知跳到我的工单 | `NotificationList.vue:135-136`：prefix **写死** `/resident/work-orders`，不看 `item.sourceType` |
| 3 | 铃铛小卡片不能跳转 | `AppHeader.vue:105-118` 铃铛下拉面板渲染通知项但**没有任何点击处理** |
| 4 | 看板「房屋占用率」应为「入住率」 | `DashboardOverview.vue:375`（卡 label）与 `:473`（文案「占用率」） |
| 5 | 看板动态：预约跳工单、公告不显示 | `DashboardOverview.vue:336-338`：「查看全部」写死 `/admin/work-orders`；动态行无逐项跳转；公告抓取是否入列需诊断（`:319` 注释表明有公告源，用户实测未见） |
| 6 | 导航栏铃铛/头像/用户名偏左 | `AppHeader.vue` `.app-header-user` 集群缺少 `margin-left: auto`（需读 CSS 段确认容器布局后修） |
| 7 | 我的预约重复日期时段 | **未定因**，两个假设：①后端列表对同一预约返回重复行（周模板 join 扩散）；②同一预约被双击重复提交产生两条数据；③卡片模板重复渲染（`ReservationListView.vue:245-252` 仅见一处 slotText，假设③概率低）。任务 R3 第一步必须用 API 原始响应 + 直查库定性，再修 |
| 8 | 预约管理周视图爆炸 | `ReservationManageView.vue`（第一轮任务 9）：网格把**全部资源**铺进 7 列，资源一多即爆；且固定周一~周日窗口无法看"几天之内"以外的日期 |

---

### 任务 R1：通知体系三修（报错 + 跳转映射 + 铃铛可点）

**文件：**
- 创建：`frontend/src/utils/notificationNav.ts` — 共享跳转映射 `notificationTarget(item: Pick<INotification,'sourceType'|'sourceId'>, role): string | null`
- 修改：`frontend/src/api/notification.ts`（响应适配）
- 修改：`frontend/src/store/notification.ts:49`（unread 适配）
- 修改：`frontend/src/components/business/NotificationList.vue:135-136`（用共享映射）
- 修改：`frontend/src/components/layout/AppHeader.vue:105-118`（铃铛面板项可点击）

- [ ] **步骤 1.1 响应适配**：读后端 `NotificationController` 确认 markAll 与 unread 的真实返回形态；`api/notification.ts` 层适配——`markAllNotificationsRead()` 返回 `{ count: number | null }`（data 为 null 时 count=null）；`listUnreadNotifications()` 若为裸数组则返回 `{ count: array.length }` 形态（或改类型并同步 store 取值处）
- [ ] **步骤 1.2 共享跳转映射**（utils/notificationNav.ts）：
  ```ts
  /** 通知 → 目标路由；sourceId 为空返回 null（不跳转） */
  export function notificationTarget(
    item: { sourceType?: string | null; sourceId?: number | null },
    role: 'RESIDENT' | 'STAFF' | 'ADMIN' | 'SUPER_ADMIN'
  ): string | null
  ```
  映射表（sourceType 前缀匹配，大小写不敏感）：WORK_ORDER → `/{prefix}/work-orders/{id}`；FEEDBACK → `/{prefix}/feedbacks/{id}`；NOTICE → `/{prefix}/notices/{id}`；RESERVATION/RESOURCE_RESERVATION → RESIDENT=`/resident/reservations`、ADMIN=`/admin/reservations`；LEASE/SYSTEM 等 → null（不跳）。prefix：RESIDENT=/resident、STAFF=/staff（仅 WORK_ORDER/FEEDBACK 有 STAFF 详情页）、ADMIN/SUPER_ADMIN=/admin。STAFF 角色遇非 WORK_ORDER 来源返回 null
- [ ] **步骤 1.3 三处接线**：NotificationList.handleClick 用映射（保留先标记已读逻辑）；AppHeader 铃铛面板项加 @click（跳转 + 已读标记 + 收起面板）；store unread 取值适配
- [ ] **步骤 1.4 后端适配核查**：确认 INotification 的 `sourceType`/`sourceId` 在后端 VO 真实存在（第一轮已知 VO 有 sourceType 用于分类过滤；sourceId 需核实）——缺则该类型跳转降级 null 并记 BEAUTIFY_NOTES 后端适配清单
- [ ] **步骤 1.5 验证**：build 全绿；Playwright——resident1 收到的反馈类通知点击落 `/resident/feedbacks/:id`、工单类落 `/resident/work-orders/:id`；铃铛面板点卡片能跳且面板收起；「全部已读」不再报错弹窗且角标清零（data null 时提示「已全部标记为已读」）；控制台零报错。脚本 `_shot_r2_notification.py`（仅本地）

### 任务 R2：我的预约重复日期时段（先定性再修）

**文件：** `frontend/src/views/resident/ReservationListView.vue`；定性涉及 api 响应与数据库直查

- [ ] **步骤 2.1 定性（必做，写进报告）**：用 resident1 会话直接调 `GET /reservations`（读原始 JSON）+ 数据库直查 `resource_reservation` 表——三选一定因：①后端返回重复行（周模板 join 扩散）→ 修前端去重（按 id 去重兜底）+ 记后端适配清单；②库里有两条相同数据（提交双击）→ 修 R3 预约子页的提交防重（this 任务只做列表侧按 id 去重防御 + 报告注明根因留 R3 防重）；③模板重复渲染 → 修模板
- [ ] **步骤 2.2 修复**：按定性结果修；无论哪种，列表渲染前按 `row.id` 去重兜底（Set 过滤）不得改变正常数据展示
- [ ] **步骤 2.3 验证**：build 全绿；resident1 实测列表无重复行；日历标记数与列表一致；控制台零报错

### 任务 R3：居民端公共资源三段流（资源列表 → 详情 → 预约子页重构）

**文件：**
- 创建：`frontend/src/views/resident/ResourceListView.vue`、`frontend/src/views/resident/ResourceDetailView.vue`
- 重构：`frontend/src/views/resident/ReservationCreateView.vue`（现为表单页，改日历+时段选择）
- 修改：`frontend/src/router/resident.ts`（+2 路由）、`frontend/src/views/resident/ReservationListView.vue`（「发起预约」入口改跳资源列表）、`frontend/src/api/resident.ts`（若缺「我的居住关系」查询封装则补，调既有端点）

- [ ] **步骤 3.1 入住社区获取**：读后端 `ResidenceRelationController` 白名单（已知含 RESIDENT）确认居民可查自己关系的端点与参数；前端补 `getMyResidenceRelations()` 封装（residentId=当前登录居民）；取**已生效**关系的 communityId 作为资源过滤键；无生效关系时资源页显引导空态（「未查到您的入住社区，请联系物业」）。端点真缺则记后端适配清单并用 userStore 社区字段兜底（如有）
- [ ] **步骤 3.2 资源列表页**（ResourceListView）：按入住社区过滤 `getResourceList(communityId)`；卡片网格（类型 SVG 图标/名称/位置/描述摘要/开放时段数）；点击 → 详情页；顶部显示当前社区名
- [ ] **步骤 3.3 资源详情页**（ResourceDetailView）：`getResource(id)` 详情 + 周循环时段表（`getTimeslotList(resourceId)` 按 dayOfWeek 分组展示：周一~周日各时段与容量）+ 醒目「预约该资源」按钮 → 预约子页（带 resourceId）；返回列表入口
- [ ] **步骤 3.4 预约子页重构**（ReservationCreateView，`?resourceId=` 必带，无则跳回列表）：
  - **分步式布局**（大表单治理示范）：左侧步骤指示（选日期 → 选时段 → 确认）或上下三段卡，禁止一屏大表单
  - 第一步**日历选日期**：月历组件（自写或 el-calendar 皮），过去日期与无开放时段日期禁选（用 `listAvailableTimeslots(resourceId, { startDate: 月初, endDate: 月末 })` 逐日判断）；月份可前后翻
  - 第二步**下拉选时段**：选中日期的开放时段列表（同一 API 取当日 slots：时间区间 + 余量 `maxBookings-currentBookings`），el-select 或时段卡选择，余量 0 禁选
  - 第三步**确认提交**：摘要卡（资源/日期/时段/预约人）+ 提交按钮；**提交防重**：提交中 disabled + 成功后跳「我的预约」并 ElMessage 成功
  - 全程不动 `createReservation` 契约（ReservationCreateDTO 现有字段）
- [ ] **步骤 3.5 入口迁移**：ReservationListView「发起预约」按钮 → `/resident/resources`（原 create 直达路由保留 redirect）；resident 首页若有预约快捷入口同步改
- [ ] **步骤 3.6 验证**：build 全绿；Playwright resident1 全流程：资源列表（只见入住社区资源）→ 详情时段表 → 预约页日历（过期/无时段日期禁选）→ 选时段（余量显示）→ 提交防重实测（连点两次仅一条）→ 我的预约出现新卡（衔接 R2 无重复）；脚本 `_shot_r2_reservation.py`

### 任务 A4：管理端看板三修（标签 + 动态跳转 + 公告显示）

**文件：** `frontend/src/views/admin/statistics/DashboardOverview.vue`

- [ ] **步骤 4.1**：`:375` 卡 label「房屋占用率」→「房屋入住率」；`:473` 文案「占用率」→「入住率」（两处 + 注释 `:155`）
- [ ] **步骤 4.2 动态逐项跳转**：动态行（`:518-532`）加 @click，按类型跳：工单 → `/admin/work-orders/{relatedId?}`（无 id 则列表）、公告 → `/admin/notices`（右栏查看态可后续带 `?noticeId=`，本任务先落列表页）、预约 → `/admin/reservations`；「查看全部」改随首条动态类型或固定工单列表（实现者按现状数据构成决策并记录）
- [ ] **步骤 4.3 公告入列诊断**：读 `:284-341` 动态拼接逻辑与 `listNotices` 调用，实测公告为何未显示（接口失败被吞？过滤条件过严？分页 size？），修复使公告正常入列
- [ ] **步骤 4.4 验证**：build 全绿；Playwright——标签文案断言、动态行点击跳转正确（工单/公告/预约各一）、公告动态可见；脚本 `_shot_r2_dashboard.py`

### 任务 A5：导航栏右侧集群右对齐

**文件：** `frontend/src/components/layout/AppHeader.vue`（CSS 段）

- [ ] **步骤 5.1**：读 `.app-header` 容器布局，右侧集群（`.app-header-user`：铃铛 + 头像下拉）加 `margin-left: auto`（或容器 `justify-content` 调整），使铃铛/头像/用户名贴右；保留移动端折行行为
- [ ] **步骤 5.2 影响面声明**：AppHeader 三端共享——居民端/服务人员端/管理端头图全部右对齐（这正是用户要的「应该右移」），游客端 GuestLayout 若复用 AppHeader 同步受益；修改前 grep 确认无端内覆盖样式依赖旧布局
- [ ] **步骤 5.3 验证**：build 全绿；三端各 1 张头部截图（admin/resident/staff）目检右对齐；控制台零报错

### 任务 A6：管理端预约管理改版（资源先行 + 月历视图）

**文件：** 重构 `frontend/src/views/admin/reservation/ReservationManageView.vue`（保留待审核右栏与违约 Tab 骨架）

- [ ] **步骤 6.1 结构改版**：`?tab=reservations` 内容改为「资源选择条 + 月历 + 日详情」三段：
  - 顶部资源下拉（必选，默认第一个资源；`getResourceList(communityId)`——communityId 用全局筛选或全部社区资源聚合，实现者按现有社区筛选能力决策并记录）
  - **月历视图**（替代固定周网格）：自写月历网格（周一开头，月可前后翻，回本月快捷），每日格显示该日预约数徽标与饱和度底色；数据源 = `listAvailableTimeslots(resourceId, { startDate: 月首, endDate: 月末 })`（逐日容量合计）+ `listReservations({ resourceId, startDate, endDate: 月末, size 逐页拉满 })`（逐日预约数）；注意数据量——月范围一次拉取，实测响应规模并记录
  - 点某日 → 右侧/下方**日详情面板**：当日时段列表（时段 + 已约/容量 + 状态色）与该日预约卡列表（沿用现有动作：通过/拒绝/完成/违约，二次确认文案逐字保留）
  - `?date=YYYY-MM-DD` 深链承载选中日；`?tab=reservations|violations` 契约不变
- [ ] **步骤 6.2 保留清单回归**：待审核右栏（通过/拒绝联动月历刷新）、违约 Tab 全部、时段详情抽屉（改造为日详情面板或保留抽屉，实现者决策记录）、五动作与二次确认
- [ ] **步骤 6.3 验证**：build 全绿；Playwright——切资源月历刷新、翻月、点日看详情、通过/拒绝后徽标联动、违约 Tab、`?date=` 深链、控制台零报错；脚本 `_shot_r2_admin_reservation.py`

### 任务 A7：社区结构增强（批量建楼/单元 + 房屋表格化增删 + 入住居民信息）

**文件：** `frontend/src/views/admin/community/StructureTreePane.vue`、`CommunityStructureView.vue`、`HouseListView.vue`（房屋管理 Tab）及其对话框组件

- [ ] **步骤 7.1 批量建楼栋**：树工具条加「批量建楼」——对话框输入楼栋名前缀 + 起始序号 + 数量（如「X栋」2~10 栋），前端循环调既有 createBuilding（逐个失败不中断，结束汇报成功/失败数）
- [ ] **步骤 7.2 批量建单元**：选中楼栋节点「批量建单元」——同模式（单元名前缀 + 数量），循环 createUnit
- [ ] **步骤 7.3 房屋表格化批量增删**：房屋管理 Tab（HouseListView）增加**批量生成**——选单元 + 楼层范围 + 每层房号起止（如 1~6 层 × 101~104），生成预览表 → 确认后循环 createHouse；表格支持**行内编辑**（房号/面积/状态等现有字段，逐行保存调既有 update）与多选删除（逐个调既有 delete，二次确认一次+逐条执行，汇报结果）；接口缺失参数记后端适配清单
- [ ] **步骤 7.4 入住居民信息（房屋维度）**：房屋管理 Tab 行操作加「住户」——抽屉内 `getHouseResidentList(houseId)`（api/resident.ts:100 已有）展示当前住户（姓名/关系类型/入住/搬出时间）；支持「办理搬出」（moveOutResidenceRelation，二次确认）与「登记住户」（跳居民-居住关系建立流程或内嵌最小表单调既有创建端点——以第一轮任务 5 收纳的关系创建能力为准，禁止新造后端调用）；**此为房屋居住信息维护，非用户账号信息**
- [ ] **步骤 7.5 结构总览 Tab 语义对齐**：结构总览 = 树 + 可视化网格 + 批量入口（7.1/7.2）；房屋管理 = 表格数据编辑 + 批量生成 + 住户信息（7.3/7.4）——两 Tab 分工按此对齐，与用户描述一致
- [ ] **步骤 7.6 验证**：build 全绿；Playwright——批量建 3 栋×2 单元实测（后清理）、批量生成 2 层×2 房（后清理）、行内编辑保存、多选删除、住户抽屉展示与办理搬出（用测试数据）、控制台零报错；脚本 `_shot_r2_community.py`

### 任务 A8：设计约定落档 + 回写

**文件：** `BEAUTIFY_NOTES.md`

- [ ] **步骤 8.1**：新增「设计约定（2026-09-12 验收后裁决）」节：**少用大表单**——分步/抽屉/分区三原则 + 阈值（超一屏或 6 输入组必拆）；本轮新增页面（预约子页/批量对话框/住户抽屉）为示范
- [ ] **步骤 8.2**：「后端适配清单」追加本轮新条目（以各任务实际核查为准）：通知 sourceType/sourceId 字段核查结论；居民「我的居住关系」端点核查结论；预约列表重复行定性结论（若属后端）；房屋/单元批量创建端点缺失（前端循环替代）；其余实现中发现的缺口
- [ ] **步骤 8.3**：进度快照节追加 R2 修复轮记录

---

## 任务依赖与顺序

R1（通知）与 A4/A5（看板/导航）相互独立可并行排查但**串行执行**（共用 dev server 与 Playwright）；R2 定性结论可能被 R3 防重引用 → R2 先于 R3；A6/A7 独立。推荐顺序：R1 → R2 → R3 → A4 → A5 → A6 → A7 → A8。

## 自检记录

1. **规格覆盖度**：用户 11 项反馈 ↔ 任务映射——重复预约=R2；预约重构三段流=R3；铃铛跳转/已读报错/反馈跳错=R1；占用率标签+动态跳转公告显示=A4；导航右移=A5；预约管理资源先行+日历式=A6；批量建楼/单元+房间表格化+住户信息=A7；大表单原则=全局约定 4 + A8 落档。无遗漏。
2. **占位符扫描**：无「待定/后续实现」；未定因项（R2 重复、R1 sourceId、R3 我的关係端点）均写明「定性步骤 + 三选一处置 + 后端适配兜底」的确定性流程。
3. **类型一致性**：`notificationTarget(item, role)` 签名在 R1 定义、R1 内三处消费；`getMyResidenceRelations()` 在 R3.1 定义、R3.2 消费；月历数据源沿用第一轮已核实函数（`listAvailableTimeslots`/`listReservations`，签名见 `api/reservation.ts:26,56`）。
