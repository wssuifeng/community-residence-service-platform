# 管理端 11 类重构 + 登录/注册/403/404 美化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）
> 或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。
>
> 本计划属 frontend-beautify 美化工作区（不属于 docs/ 文档体系），状态以本文件
> 复选框 + 根目录 BEAUTIFY_NOTES.md 为准。工作方法沿用 skill `mockup-to-page`
> 五步法（读图拆解→对齐现状→实现→验证→总结）；本计划已替执行者完成
> 「读图拆解」与「页面架构裁决」，执行者从「对齐现状」起步。

**目标：** 管理端 20 个侧栏菜单项收敛为 11 类页面（对照 `design-mockups/admin/` 11 张设计稿），消灭子页面海；登录/注册/403/404 四个无设计稿页面先补设计稿再实现；全程不删任何功能、交互、接口调用。

**架构：** 「11 侧栏项 + 4 隐藏下钻详情路由」双层结构。11 类中 6 类为页内 Tab 页、2 类为主从双栏页、3 类为单列表页；页内 Tab 用 `?tab=` query 承载深链（沿用 staff 工单列表先例）；低频配置（服务类别/社区绑定/评价跟进/公告编辑）下沉为抽屉与右侧面板，不再占用路由。共享基建（新侧栏、路由重组、页面头组件、截图验证脚本）集中在任务 0/1，后续任务只做页面。

**技术栈：** Vue 3.5 + TS + Element Plus（可用不依赖）+ 自写组件 + variables.css token 体系；验证 = `npm run build`（含 vue-tsc）+ Playwright 截图对比 + 交互回归脚本。

## 执行状态（2026-09-12 收尾时点，明细见 `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/progress.md` 账本）

| 任务 | 状态 | 备注 |
|------|------|------|
| 0 落档 | ✅ | D1/D2/D3 + 配色纪律 + 后端适配清单入 BEAUTIFY_NOTES |
| 1 基建 | ✅ | 侧栏 20→11 项、路由重组 + 22 条 redirect、AdminPageHeader、9 容器壳。注：步骤 1.3 草案中 `/admin/system` 的 `roles:['SUPER_ADMIN']` 被 §二第 11 行裁决取代（路由不加 roles 保 ADMIN 日志可达，菜单隐藏 + Tab 空态降级） |
| 2 看板 | ✅ | 概览/详细统计双 Tab；AdminStatCard 沉淀 |
| 3 社区结构 | ✅ | 树+房屋网格+双 Tab；顺修 3 处契约漂移（时段周模板/资源类型枚举/电话校验） |
| 4 工单 | ✅ | 统计卡+类别抽屉+详情三栏；顺修死筛选与分页两 bug |
| 5 居民 | ✅ | 三 Tab；顺修押金静默丢弃与 leaseStartDate 恒空 |
| 6 租住 | ✅ | 四 Tab+进度条；发现前端 lease 数据层整体过期并按实测契约重写（1 轮修复闭环） |
| 7 公告 | ✅ | 主从双栏删 3 视图；DRAFT 状态实存（简报假设被源码推翻） |
| 8 反馈 | ✅ | 主从聊天；WS 双浏览器实测 0 秒实时 |
| 9 预约 | ✅ | 周视图+待审核右栏+违约 Tab；顺修 confirm/complete 缺 reason 必 400 |
| 10 房源 | ✅ | 摄影卡片网格；HousingVO 漂移收口+押金保存修复 |
| 11 评价 | ✅ | 统计头+跟进抽屉；顺修跟进三字段必 400 与双发请求 |
| 12 系统 | ✅ | 三 Tab+绑定抽屉；50-1b 配置校验能力逐字保真 |
| 13 auth 稿 | ✅（变体） | 4 张定稿已由并行会话生成，本计划核验+manifest 补录 |
| 14 登录/注册 | ⏭️ 裁决跳过 | 已由并行会话按 auth 定稿实现（BEAUTIFY_NOTES 32~41 行），重做将覆盖其成果；核验并入任务 16 |
| 15 403/404 | ⏭️ 裁决跳过 | 同上 |
| 16 回归+回写 | ✅ | build 全绿；管理端 13 脚本 770 断言 PASS + 13 断言 FAIL（全部定因为脚本资产滞后/数据漂移，非产品缺陷）、staff 三脚本/auth 四页/游客居民端抽查全 PASS、控制台零报错；BEAUTIFY_NOTES 收尾回写完成（回归明细 `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/task-16-regression.md`） |

本轮全程未做任何 git 提交（裁决 D3），全部改动在工作树待用户审查。

---

## 一、前置裁决项（2026-09-12 用户已全部裁决）

| # | 裁决项 | 结论 |
|---|--------|------|
| D1 | 全站底色 `--color-bg` | ~~管理端局部暖白画布~~ **已修订（2026-09-12 二次裁决）：全站画布统一冷灰**——`--admin-canvas`/`--color-bg-warm` 均改为 `var(--color-bg)` 兼容别名，auth 四页底色同步切换；管理端仅侧栏/白卡自成体系 |
| D2 | 公告管理契约漂移 | **前端表单保持现状（priority/type/expireTime 照常提交，不删字段）**。后端主工作区测试已跑完，契约差距由本工作区记录在案（BEAUTIFY_NOTES「后端适配清单」），后续在后端侧做适配（VO 暴露 is_pinned、保存接口接收漂移字段等） |
| D3 | 截图/脚本/提交纪律 | **本轮一律不 git commit**（截图、脚本、代码全留工作树）；截图产物按现有规则本地保留不上传；一轮做完后由用户整轮审查指出问题再迭代 |

## 一.1 配色纪律（全计划生效，2026-09-12 用户要求）

颜色要**参考设计稿成体系地搭配，不是单点抄色**。每个页面取色时按以下层次从稿中推导关系，再映射到 token（增量）：

1. **画布层** → 卡片层 → 悬停/按压层：三层的明度梯度（稿：暖白画布 → 纯白卡 → 微降明度交互态）；
2. **语义软色对**：统计卡图标底、状态底色用语义色的 soft 变体（如蓝 10% 底 + 蓝实色图标），与既有 `--color-*-soft` token 家族对齐，缺的增量补；
3. **文字层级**：主文/次文/弱化文三档灰阶从稿中取（管理端深蓝侧栏上的白字梯度单独一套）；
4. **品牌蓝的使用密度**：主按钮/激活态/链接用 `--color-primary`，禁止把品牌蓝撒在装饰上稀释其指向性；
5. 组件内**禁止散落硬编码 hex**——一律走 variables.css token，页面间同语义必须同值。

## 二、页面架构裁决（回答「11 页是否足够、是否要子页面」）

**结论：11 个侧栏入口足够，不需要恢复子页面海；但「一类一页」≠ 一类一个文件。**
按 11 张设计稿的实际结构 + 现有 33 视图 ~10900 行的复杂度测算，页面结构分三档：

| # | 侧栏项 | 设计稿结构（已逐张读图确认） | 吸收的现有视图（行数） | 本计划裁决 |
|---|--------|------------------------------|------------------------|------------|
| 1 | 看板 | 4 趋势卡 + 30 天趋势线图 + 工单状态环图 + 房屋状态条 + 最新动态 | DashboardView(332) + StatisticsView(307) | **2 Tab**：概览 / 详细统计。详细统计 307 行塞不进概览，稿侧栏的「统计」独立项按 11 类定稿并入 |
| 2 | 社区结构 | 左树（社区→楼栋→单元）+ 右侧楼栋详情 + 房屋状态网格（按层×单元，四色状态） | CommunityList(300) + CommunityDetail(241) + Building(288) + Unit(372) + House(691) + PublicResource(614) | **3 Tab**：结构总览（树+房屋网格，楼栋/单元增删改走对话框）/ 房屋管理（保留查询表格）/ 公共资源（保留 CRUD 表格）。**社区详情保留为隐藏下钻路由**（241 行塞不进右栏）。此为最重聚类（~2500 行），禁止压成单文件单页 |
| 3 | 工单 | 4 统计卡 + 筛选条 + 表格（派单/详情操作） | WorkOrderList(273) + ServiceCategory(278) + WorkOrderDetail(465) | 单页列表；**服务类别 → 页头「服务类别」按钮开抽屉**（低频配置）。工单详情保留隐藏下钻路由。日期范围筛选删除（后端 `WorkOrderService.page()` 无 startTime/endTime 参数，现状是死控件 bug，借机修掉） |
| 4 | 居民 | 3 统计卡 + 三 Tab（居民列表/入住申请(3)/居住关系）+ 筛选表格 | ResidentList(192) + ResidenceApplication(292) + ResidenceRelation(282) + ResidentDetail(264) | **3 Tab**（设计稿已明示）；居民详情保留隐藏下钻路由 |
| 5 | 租住 | 到期提醒条 + 四 Tab（在租/即将到期/已到期/历史）+ 剩余天数进度条 | LeaseList(523) | **4 Tab**（单视图按日期与状态派生分组，非独立请求）；续租/退租按钮映射现有「办理搬出/归档」等真实动作 |
| 6 | 公告 | 左列表（置顶标/状态标/搜索）+ 右编辑面板（标题/正文/发布范围/发布按钮） | NoticeList(261) + NoticeCreate(372) + NoticeDetail(359) | 单页主从：创建/编辑/详情全走右侧面板与查看抽屉，**删 2 个子路由**（notices/create、notices/:id）。稿中「草稿」状态后端不存在（实际 PUBLISHED/OFFLINE）、富文本编辑器超后端纯文本契约——按 mockup-to-page 硬规则裁剪；契约漂移按裁决项 D2 |
| 7 | 反馈 | 左列表（状态色点卡片+搜索）+ 右会话聊天（回复框/办结） | FeedbackList(180) + FeedbackDetail(528) | 单页主从，**删详情子路由**（feedbacks/:id）；WS 实时推送（/topic/feedback/{id}）与轮询兜底保留；「转工单」按钮省略（无后端支撑） |
| 8 | 预约 | 3 统计卡 + 预约周视图网格（资源×时段，占用数着色）+ 右栏待审核（通过/拒绝）+ 违约记录入口 | ResourceReservationList(254) + ViolationList(183) | 单页：周视图 + 右栏待审核；**违约记录 → 第二 Tab**。周视图数据源 = `listReservations({ startDate, endDate, resourceId?, status?, size })` 按日分组（类型已含日期参数；实现前必须按「筛选控件不造假」纪律读 `ReservationController`/`ReservationService` 源码确认参数真实生效，不支持则降级为「日历标记 + 列表」，降级决策记录进总结） |
| 9 | 房源 | 统计卡 + 摄影卡片网格（编辑/时段/下架操作） | HousingList(536) + HousingDetail(549) + ViewingAppointmentList(254) | **2 Tab**：房源卡片网格 / 看房预约表格。房源详情保留隐藏下钻路由； HousingVO 字段漂移（houseAddress 等 5 字段）沿用游客端修法：类型追加真实字段、读取侧全量切真实字段 |
| 10 | 评价 | 综合评分卡 + 评分分布条 + 三 Tab（全部/待跟进(2)/已跟进）+ 评价卡片（发起跟进） | EvaluationList(179) + EvaluationFollowup(252) | 单页：统计头 + 3 Tab + 卡片列表；**发起跟进 → 抽屉**（内嵌跟进时间线），**删 followup 子路由** |
| 11 | 系统 | 三 Tab（系统用户/全局配置/操作日志）+ 角色筛选 | SysUserList(487) + SysUserCommunity(238) + OperationLog(269) + GlobalConfig(177) | **3 Tab**（设计稿已明示）；社区绑定改抽屉（**删 sys-users/:id/communities 子路由**）；「系统」菜单项仅 SUPER_ADMIN 可见时仍需拆粒度：系统用户/全局配置仅超管，操作日志 ADMIN 可用 → Tab 内按角色降级展示「无权限」空态，菜单整体对 ADMIN 隐藏（与现状 meta roles 一致） |

**隐藏下钻路由（保留 4 个，不算侧栏页）**：社区详情 `/admin/communities/:id`、工单详情 `/admin/work-orders/:id`、居民详情 `/admin/residents/:id`、房源详情 `/admin/housings/:id`。这四个详情页均为复杂读页（241~549 行），压进弹层/右栏会重蹈「单页过载」，故独立路由 + 面包屑返回，仅做视觉美化不做结构合并。

**删除的路由（内容并入上述页面，7 个）**：`notices/create`、`notices/:id`、`feedbacks/:id`、`evaluations/:id/followup`、`sys-users/:id/communities`、`statistics/details`、`notifications` 侧栏入口（页面保留为隐藏路由，铃铛下拉已覆盖日常入口）。全部旧路径设 redirect，保书签与回归脚本不断链。

## 三、文件结构

**新建：**
- `frontend/src/views/admin/AdminPageHeader.vue` — 管理端页头（大标题+副题+右侧操作区插槽），11 类页共用
- `frontend/src/views/admin/community/CommunityStructureView.vue` — 社区结构 3 Tab 容器（树/房屋/公共资源）
- `frontend/src/views/admin/resident/ResidentManageView.vue` — 居民 3 Tab 容器
- `frontend/src/views/admin/notice/NoticeManageView.vue` — 公告主从页
- `frontend/src/views/admin/feedback/FeedbackManageView.vue` — 反馈主从页
- `frontend/src/views/admin/reservation/ReservationManageView.vue` — 预约周视图页
- `frontend/src/views/admin/housing/HousingManageView.vue` — 房源 2 Tab 容器
- `frontend/src/views/admin/evaluation/EvaluationManageView.vue` — 评价统计头+Tab 页
- `frontend/src/views/admin/auth/SystemManageView.vue` — 系统 3 Tab 容器
- `frontend/src/views/admin/statistics/DashboardTabView.vue` — 看板 2 Tab 容器（旧 DashboardView/StatisticsView 改为子组件引入）
- `frontend/src/components/admin/AdminStatCard.vue` — 管理端统计卡（图标+数值+趋势/环），看板/工单/居民/预约共用
- `frontend/_shot_admin.py`、`frontend/_regress_admin.py` — 截图与回归脚本（模板见任务 0）

**重写/大改：**
- `frontend/src/router/admin.ts` — 路由全量重组（11 主路由 + 4 隐藏详情 + 12 条 redirect）
- `frontend/src/components/layout/AppSidebar.vue` — 菜单 20 项 → 11 项
- `frontend/src/views/admin/AdminLayout.vue` — 侧栏样式按稿（深色 240px、active 左缘高亮）、内容区暖白画布
- `frontend/src/styles/variables.css` — 仅增量 token（`--admin-sidebar-bg` 等，见任务 1）

**改造（保逻辑换壳）：** `statistics/DashboardView.vue`、`statistics/StatisticsView.vue`、`community/CommunityListView.vue`、`community/HouseListView.vue`、`community/PublicResourceListView.vue`、`workorder/WorkOrderListView.vue`、`workorder/WorkOrderDetailView.vue`、`resident/ResidentListView.vue`、`ResidenceApplicationListView.vue`、`ResidenceRelationListView.vue`、`ResidentDetailView.vue`、`lease/LeaseListView.vue`、`housing/HousingListView.vue`、`HousingDetailView.vue`、`housing/ViewingAppointmentListView.vue`、`workorder/ServiceCategoryListView.vue`（改造为抽屉组件 `ServiceCategoryDrawer.vue`）、`auth/SysUserListView.vue`、`SysUserCommunityView.vue`（改造为抽屉 `SysUserCommunityDrawer.vue`）、`auth/OperationLogListView.vue`、`resident/GlobalConfigView.vue`、`evaluation/EvaluationListView.vue`、`EvaluationFollowupView.vue`（改造为抽屉 `FollowupDrawer.vue`）

**删除（末尾统一删，避免中途断链）：** `notice/NoticeCreateView.vue`、`notice/NoticeDetailView.vue`、`feedback/FeedbackDetailView.vue`（内容迁入主从页后）

**不受影响：** 游客/居民/服务人员端全部页面、`components/common/*`（StatusTag/Pagination 继续复用）、`api/*`（零接口变更）、后端。

---

## 共享工作约定（每个任务都遵守）

1. **并行会话纪律**（BEAUTIFY_NOTES）：改共享文件（variables.css/AppHeader/AppSidebar/Pagination/StatusTag）前先 `git diff` 看对方最新状态；token 只增量不改既有值。
2. **验证门槛（缺一不可）**：`cd frontend && npm run build` 全绿（vue-tsc 0 错误）；新页对照稿截图（整页 + 关键区域裁剪）；该页保留清单逐项回归；控制台零报错。
3. **截图脚本约定**：dev server 游离进程在 5273 端口（不可用则 `npm run dev` 自起）；登录态用 `admin1 / Admin123456`（超管场景 `superadmin / Admin@123456`）；截图存 `frontend/acceptance-shots/`，命名 `admin-<page>-{full,1536x1024}.png`。
4. **Commit 规范（本轮挂起）**：按 D3 裁决，本轮所有任务**不执行 git commit**，改动全部留在工作树待用户整轮审查；各任务末尾的 Commit 步骤本轮跳过，仅把该任务改动文件清单记入总结。
5. **数据决策留痕**：每页「稿有但数据无支撑」的元素按三选一（近似/静态/省略）记录进 BEAUTIFY_NOTES「管理端数据决策」节；MOCK 占位集中标注 `【MOCK 临时占位区块】` 可全局检索。
6. **筛选控件不造假**：实现筛选前读后端 Controller/Service 源码确认参数真实存在；稿有后端无 → 省略并记录。
7. **配色纪律**：按 §一.1 执行——从设计稿推导层次关系映射 token，禁止单点抄色与组件内硬编码 hex。

---

### 任务 0：工作区收编 + 裁决落档（本轮不 commit）

**文件：** 修改 `BEAUTIFY_NOTES.md`

- [ ] **步骤 0.1**：确认并行会话已收工（游客/居民/服务人员端不再有活动改动），`git status` 与 BEAUTIFY_NOTES 进度快照对照
- [ ] **步骤 0.2**：**不做任何 git 提交**（D3 裁决）；现有工作树改动原样保留
- [ ] **步骤 0.3**：D1/D2/D3 结论落档 BEAUTIFY_NOTES：管理端用 `--admin-canvas` 局部暖白（全局 token 不动）；新增「后端适配清单（管理端美化发现）」节——公告表单 priority/type/expireTime 待后端保存接口接收、NoticeVO 待暴露 is_pinned，及既有待补接口 ①~⑧ 引用；截图与脚本本地保留不入库的约定记一笔

### 任务 1：AdminLayout 新侧栏 + 路由重组 + 页头组件（基建）

**文件：**
- 修改：`frontend/src/router/admin.ts`（全量重写）
- 修改：`frontend/src/components/layout/AppSidebar.vue`
- 修改：`frontend/src/views/admin/AdminLayout.vue`
- 创建：`frontend/src/views/admin/AdminPageHeader.vue`
- 修改：`frontend/src/styles/variables.css`（仅增量）

- [ ] **步骤 1.1 增量 token**（variables.css 追加，不动既有值）：
  ```css
  /* 管理端（frontend-beautify 增量） */
  --admin-sidebar-bg: #10233f;        /* 稿01/02 深蓝侧栏 */
  --admin-sidebar-active: #3b6dff;
  --admin-sidebar-width: 232px;
  --admin-canvas: #f7f5f2;            /* 内容区画布；若 D1 选切全局则与此值一致 */
  --shadow-card: 0 1px 3px rgba(16, 35, 63, 0.06), 0 4px 16px rgba(16, 35, 63, 0.06);
  ```
- [ ] **步骤 1.2 新侧栏**：AppSidebar menuGroups 重写为 11 项单组（看板/社区结构/工单/居民/租住/公告/反馈/预约/房源/评价/系统），每项配内联 SVG 图标（currentColor 单色，沿用 AppLogo 资产纪律）；「系统」项 `roles: ['SUPER_ADMIN']` 沿用现有角色过滤逻辑；激活态 = 蓝底白字圆角块（稿 01 样式）；旧 20 项路径全部删除（redirect 在路由层兜底）
- [ ] **步骤 1.3 路由重组**（admin.ts 全量重写，关键结构如下）：
  ```ts
  // 11 主路由（侧栏项）——Tab 用 query 承载，深链契约与 staff 工单列表一致
  { path: 'dashboard',      name: 'AdminDashboard',    component: DashboardTabView },
  { path: 'community',      name: 'AdminCommunity',    component: CommunityStructureView },
  { path: 'work-orders',    name: 'AdminWorkOrders',   component: WorkOrderListView },
  { path: 'residents',      name: 'AdminResidents',    component: ResidentManageView },
  { path: 'leases',         name: 'AdminLeases',       component: LeaseListView },
  { path: 'notices',        name: 'AdminNotices',      component: NoticeManageView },
  { path: 'feedbacks',      name: 'AdminFeedbacks',    component: FeedbackManageView },
  { path: 'reservations',   name: 'AdminReservations', component: ReservationManageView },
  { path: 'housings',       name: 'AdminHousings',     component: HousingManageView },
  { path: 'evaluations',    name: 'AdminEvaluations',  component: EvaluationManageView },
  { path: 'system',         name: 'AdminSystem',       component: SystemManageView, meta: { title: '系统管理', roles: ['SUPER_ADMIN'] } },
  // 4 隐藏下钻详情（无侧栏项，面包屑返回）
  { path: 'communities/:id',  name: 'AdminCommunityDetail', component: CommunityDetailView },
  { path: 'work-orders/:id',  name: 'AdminWorkOrderDetail', component: WorkOrderDetailView },
  { path: 'residents/:id',    name: 'AdminResidentDetail',  component: ResidentDetailView },
  { path: 'housings/:id',     name: 'AdminHousingDetail',   component: HousingDetailView },
  { path: 'notifications',    name: 'AdminNotifications',   component: NotificationListView },  // 隐藏保留
  // 兼容 redirect（旧书签/脚本不断链）
  { path: '', redirect: '/admin/dashboard' },
  { path: 'statistics/dashboard', redirect: '/admin/dashboard' },
  { path: 'statistics/details',   redirect: '/admin/dashboard?tab=details' },
  { path: 'communities',  redirect: '/admin/community' },
  { path: 'buildings',    redirect: '/admin/community' },
  { path: 'units',        redirect: '/admin/community' },
  { path: 'houses',       redirect: '/admin/community?tab=houses' },
  { path: 'resources',    redirect: '/admin/community?tab=resources' },
  { path: 'residence-applications', redirect: '/admin/residents?tab=applications' },
  { path: 'residence-relations',    redirect: '/admin/residents?tab=relations' },
  { path: 'configs',       redirect: '/admin/system?tab=configs' },
  { path: 'service-categories', redirect: '/admin/work-orders' },   // 服务类别改抽屉
  { path: 'resource-reservations', redirect: '/admin/reservations' },
  { path: 'violations',  redirect: '/admin/reservations?tab=violations' },
  { path: 'viewing-appointments', redirect: '/admin/housings?tab=viewings' },
  { path: 'notices/create', redirect: '/admin/notices?action=create' },
  { path: 'notices/:id',    redirect: '/admin/notices' },          // 详情并入主从右栏
  { path: 'feedbacks/:id',  redirect: '/admin/feedbacks' },        // 详情并入主从右栏
  { path: 'evaluations/:id/followup', redirect: '/admin/evaluations' },
  { path: 'sys-users',              redirect: '/admin/system?tab=users' },
  { path: 'sys-users/:id/communities', redirect: '/admin/system?tab=users' },
  { path: 'operation-logs', redirect: '/admin/system?tab=logs' },
  ```
  注意：`notices/:id` redirect 会让 `/admin/notices/abc` 与 4 个详情路由中 `work-orders/:id` 等无冲突；redirect 需放在对应具名路由**之后**避免吞路由（vue-router 静态段优先级高于动态段，天然安全，但 Tab redirect 带 query 时用 `redirect: { name: ..., query: {...} }` 对象写法保类型）
- [ ] **步骤 1.4 AdminPageHeader 组件**：props `title/subtitle`，默认插槽放操作按钮；样式按稿（28px 加粗标题 + 14px 灰副题 + 右对齐操作区），11 类页统一引用
- [ ] **步骤 1.5 AdminLayout**：侧栏容器换新 token（深蓝底、白字、圆角激活块、可折叠留到后续）；面包屑行保留；内容区背景 `var(--admin-canvas)`
- [ ] **步骤 1.6 过渡骨架**：任务 2~12 未实施的 8 个容器视图先建空壳（页头 + 「重构进行中」占位），保证 11 个路由全部可点不白屏
- [ ] **步骤 1.7 验证**：`npm run build` 全绿；截图 `admin-shell-1536x1024.png` 对照稿 01/02 的侧栏（图标/激活态/深蓝底）；逐个点击 11 项菜单 + 抽查 5 条旧路径 redirect（如 `/admin/buildings` → 社区结构）；ADMIN 账号确认「系统」菜单不可见
- [ ] **步骤 1.8 Commit**：`feat(beautify-admin): 基建——侧栏20项收敛11项 + 路由重组 + 页头组件`

### 任务 2：看板（概览 + 详细统计 2 Tab）

**文件：** 创建 `statistics/DashboardTabView.vue`；改造 `statistics/DashboardView.vue`、`statistics/StatisticsView.vue`（转子组件，逻辑不动）

- [ ] **步骤 2.1 对齐现状**：清点 DashboardView 的 16 卡片数据源（listWorkOrders 多路统计 / 运营看板接口）与 StatisticsView 的图表配置（ECharts 实例），列保留清单
- [ ] **步骤 2.2 实现**：容器页 AdminPageHeader（「运营看板」+ 社区筛选沿用 P2 既有能力）+ 白卡 Tab 条（概览/详细统计，`?tab=` 契约）；概览 Tab 按稿重排：4 张 AdminStatCard（本月工单/待处理/入住率/满意度，趋势箭头红升绿降口径与 staff 工作台一致）→ 2/3+1/3 两栏（近30天趋势折线 + 状态分布环图）→ 房屋状态堆叠条 + 最新动态列表（现有数据源映射，无支撑项按共享约定 5 记录）
- [ ] **步骤 2.3 验证**：build 全绿；截图对照稿 02（P0 布局/P1 配色）；Tab 深链 `/admin/dashboard?tab=details` 直达；社区筛选切换数据刷新回归
- [ ] **步骤 2.4 Commit**：`feat(beautify-admin): 看板——概览/详细统计双Tab + 趋势卡与图表重排`

### 任务 3：社区结构（3 Tab：结构树 / 房屋 / 公共资源）

**文件：** 创建 `community/CommunityStructureView.vue`；改造 `CommunityListView.vue`、`HouseListView.vue`、`PublicResourceListView.vue`、`BuildingListView.vue`、`UnitListView.vue`、`CommunityDetailView.vue`（隐藏下钻美化）

- [ ] **步骤 3.1 对齐现状**：清点 6 视图保留清单——四路 CRUD（community/building/unit/house/public_resource 各自 api 函数）、社区删除入口（级联二次确认，R1/R6 v1.1 能力）、房屋状态筛选、公共资源时段管理
- [ ] **步骤 3.2 Tab 三容器**：`?tab=tree|houses|resources`；houses/resources Tab 直接内嵌改造后的 House/PublicResource 表格视图（换壳：AdminPageHeader + 白卡容器 + 统计卡条）
- [ ] **步骤 3.3 结构树 Tab（对照稿 01）**：顶部 4 统计卡（楼栋/单元/房屋/入住率，来源现有统计接口或前端聚合，无接口支撑的入住率按共享约定标注）；左栏树（el-tree 或自写，社区→楼栋→单元三级，节点悬浮显示操作按钮：楼栋/单元增删改走对话框——复用 Building/Unit 视图现有表单逻辑抽成对话框组件）；右栏选中节点详情（楼栋：楼号/层数/单元数 + 编辑按钮；单元：其下房屋网格，按层×户排列，四色状态 = 已入住/空置/维修中/欠费，色值用语义 token + soft 变体；网格块点击 → 跳房屋详情或弹房屋卡）。稿中的「1号楼/1单元」命名与楼层行列布局按真实数据动态渲染，不硬编码
- [ ] **步骤 3.4 社区详情下钻**：CommunityDetailView 换壳美化（保留全部数据绑定），从树根节点「社区设置」进入
- [ ] **步骤 3.5 验证**：build 全绿；截图对照稿 01（树/网格/统计卡三区域裁剪）；回归清单——楼栋/单元/房屋/公共资源四类 CRUD 各 1 轮（增→查→改→删）、社区删除二次确认、树节点切换右栏联动、`?tab=` 三路深链
- [ ] **步骤 3.6 Commit**：`feat(beautify-admin): 社区结构——树+房屋网格主从 + 房屋/公共资源Tab（6视图收敛）`

### 任务 4：工单管理（列表 + 服务类别抽屉 + 详情下钻美化）

**文件：** 改造 `workorder/WorkOrderListView.vue`；创建 `workorder/ServiceCategoryDrawer.vue`（从 ServiceCategoryListView 迁移）；改造 `workorder/WorkOrderDetailView.vue`

- [ ] **步骤 4.1 对齐现状**：清点 WorkOrderList 保留清单（listWorkOrders 五路调用/状态 Tab/Pagination/SearchBar/StatusTag）；**已知 bug 顺修**：日期筛选死控件（fetchList 不发 startTime/endTime）→ 按稿删日期控件；Pagination 未挂处理函数 → 修
- [ ] **步骤 4.2 列表页**：AdminPageHeader（「工单管理」+ 副题 + 「服务类别」按钮）+ 4 AdminStatCard（待受理/待派单/处理中/今日完成——口径对齐后端真实参数，「今日」若无时间过滤参数则标累计并记录，同 staff 工作台④号遗留）+ 筛选条（状态/分类/关键字——均后端真实支持）+ 白卡表格（工单号/标题/提交人/分类/紧急度/状态/服务人员/时间/操作）
- [ ] **步骤 4.3 服务类别抽屉**：ServiceCategoryListView 的树表 + CRUD 迁入 el-drawer（416px 右侧抽屉）；原路由 redirect 已在任务 1 兜底
- [ ] **步骤 4.4 详情下钻美化**：面包屑 + 状态条白卡 + 处理记录时间线（字段用 `newStatus/content` 真实契约，居民端已修齐同款）；保留派单/改派/关闭全部管理动作与附件查看
- [ ] **步骤 4.5 验证**：build 全绿；截图对照稿 03；回归——状态 Tab 切换/搜索/分页（重点验 Pagination 修复）/服务类别抽屉 CRUD/派单动作全链路
- [ ] **步骤 4.6 Commit**：`feat(beautify-admin): 工单管理——统计卡+筛选表格 + 服务类别抽屉（顺修死筛选与分页）`

### 任务 5：居民管理（3 Tab + 详情下钻美化）

**文件：** 创建 `resident/ResidentManageView.vue`；改造 `ResidentListView.vue`、`ResidenceApplicationListView.vue`、`ResidenceRelationListView.vue`、`ResidentDetailView.vue`

- [ ] **步骤 5.1 对齐现状**：清点三视图保留清单（居民查询/入住申请审批流 approve-reject/居住关系 CRUD 与解除；注意 ResidenceRelationListView 重置后不重新加载的已知问题顺修）
- [ ] **步骤 5.2 实现**：容器 + 3 AdminStatCard（在住居民/待审核申请数/本月新增——待审核数用 status=PENDING total，其余按真实数据源映射）+ 白卡 Tab 条（居民列表/入住申请/居住关系，`?tab=` 契约，稿 04 已明示）；居民列表行加头像首字 + 身份/状态 StatusTag
- [ ] **步骤 5.3 详情下钻美化**：居民详情换壳（信息卡 + 居住关系列表 + 关联数据），保留全部绑定
- [ ] **步骤 5.4 验证**：build 全绿；截图对照稿 04；回归——入住申请 approve/reject 全流程、居住关系增删、`?tab=` 三路深链、重置后自动重载
- [ ] **步骤 5.5 Commit**：`feat(beautify-admin): 居民管理——三Tab收敛（列表/申请/关系）+ 详情美化`

### 任务 6：租住管理（4 Tab + 剩余天数进度条）

**文件：** 改造 `lease/LeaseListView.vue`（单文件内重构）

- [ ] **步骤 6.1 对齐现状**：清点保留清单（租住 CRUD/审批/搬出/归档动作、即将到期 Tab 现状、lease_reminder 揀相关展示）
- [ ] **步骤 6.2 实现**：顶部到期提醒条（黄色软底，N 份即将到期 + M 份已到期，数字由列表数据派生，点击切 Tab）+ 白卡四 Tab（在租/即将到期/已到期/历史——客户端分组：在租=已生效且未临期，临期阈值与后端定时任务口径一致（30 天），历史=已搬出+已归档）+ 表格增「剩余天数」列：细进度条（绿>90天/橙30~90/红<30，含负数已到期）+ 状态 StatusTag；操作列对齐真实动作（续租无后端支撑 → 省略按钮，展示「详情/搬出/归档」等现有能力）
- [ ] **步骤 6.3 验证**：build 全绿；截图对照稿 05（提醒条/进度条两处区域裁剪）；回归——四 Tab 分组数字与列表一致、审批与搬出动作、`?tab=` 深链
- [ ] **步骤 6.4 Commit**：`feat(beautify-admin): 租住管理——到期提醒条+四Tab+剩余天数进度条`

### 任务 7：公告管理（主从双栏，删 2 子路由）

**文件：** 创建 `notice/NoticeManageView.vue`；改造 `notice/NoticeListView.vue`（迁移为左栏）；删除 `NoticeCreateView.vue`、`NoticeDetailView.vue`（内容迁入）

- [ ] **步骤 7.1 对齐现状**：清点保留清单（listNotices/createNotice/updateNotice/deleteNotice/publishNotice/withdrawNotice/listNoticeViewers 查看记录；**表单契约按裁决项 D2 保持现状**——priority/type/expireTime 字段照常保留与提交，不删不改；置顶防御式逻辑保留——VO 暴露 isPinned 则渲染置顶标；差距记入 BEAUTIFY_NOTES「后端适配清单」，后续后端适配）；稿中「草稿」状态后端不存在（实际 PUBLISHED/OFFLINE），展示用真实状态；正文保持纯文本 textarea（不引富文本依赖）
- [ ] **步骤 7.2 实现**：左栏（搜索 + 公告卡列表：置顶红标/状态 StatusTag/阅读数·发布时间，状态只用后端真实 PUBLISHED/OFFLINE）；右栏两种态——查看态（标题/元信息/正文/发布范围/查看记录入口）与编辑态（标题输入/正文 textarea（**纯文本，不引富文本依赖**）/发布范围社区多选/保存并发布·存草稿改「保存不发布」·下线按钮）；「发布公告」按钮 → 右栏编辑空态；删改操作带二次确认（沿用现有）
- [ ] **步骤 7.3 验证**：build 全绿；截图对照稿 06（右栏编辑态单截一张）；回归——创建→发布→查看→下线→删除全链路、发布范围多选、旧路由 `/admin/notices/create` redirect、查看记录列表
- [ ] **步骤 7.4 Commit**：`feat(beautify-admin): 公告管理——主从双栏合一页（表单契约对齐后端，删2子路由）`

### 任务 8：反馈管理（主从聊天，删详情子路由）

**文件：** 创建 `feedback/FeedbackManageView.vue`；改造 `feedback/FeedbackListView.vue`（迁移为左栏）；删除 `FeedbackDetailView.vue`（内容迁入）

- [ ] **步骤 8.1 对齐现状**：清点保留清单（listFeedbacks/getFeedbackDetail/reply/close 动作、附件上传删除、状态机 PENDING/IN_SESSION/CLOSED 真实枚举、WS /topic/feedback/{id} 订阅与轮询兜底、已办结禁增删附件规则）
- [ ] **步骤 8.2 实现**：左栏（搜索 + 状态色点卡片：标题/提交人/日期/状态胶囊，色点=状态语义色）+ 右栏会话（提交人信息条/消息气泡流（居民左·管理员右）/回复框 + 发送/办结按钮）；会话切换 = 选中左栏项加载右栏（route 不变，组件内 state）；WS 实时消息插入现有气泡流
- [ ] **步骤 8.3 验证**：build 全绿；截图对照稿 07；回归——受理→回复→办结全链路、附件上传预览删除、双开浏览器实测 WS 实时到达（或轮询兜底 30s 内到达）、旧路由 redirect
- [ ] **步骤 8.4 Commit**：`feat(beautify-admin): 反馈管理——列表+会话主从页（WS实时保留，删详情子路由）`

### 任务 9：预约管理（周视图 + 待审核右栏 + 违约 Tab）

**文件：** 创建 `reservation/ReservationManageView.vue`；改造 `ResourceReservationListView.vue`、`ViolationListView.vue`（内容迁移）

- [ ] **步骤 9.1 数据预检（不造假纪律）**：读 `backend/.../reservation/ReservationController.java` + Service 确认 list 接口 `startDate/endDate/resourceId/status` 参数真实生效（前端类型已有，需后端核实）；`listAvailableTimeslots(resourceId, {startDate,endDate})` 周区间返回形态。**不支持则执行降级方案**：周视图改「7 日日历条 + 按日筛选列表」，决策写入 BEAUTIFY_NOTES
- [ ] **步骤 9.2 对齐现状**：清点保留清单（listReservations 查询、confirm/reject/complete/cancel/violate 五动作、违约记录列表与筛选）
- [ ] **步骤 9.3 实现**：3 AdminStatCard（今日预约/待审核/本月违约——数字由周数据与 total 派生）+ 周视图（`listReservations({ startDate: 周一, endDate: 周日, size: 200 })` 客户端按 日期×时段 分组渲染格子：资源名+已约/容量+状态色，格子点击开预约详情抽屉；资源筛选下拉（稿顶部「全部资源」）切换时按 resourceId 重查）+ 右栏待审核队列（status=PENDING 列表，通过/拒绝直接调现有动作，成功后格子刷新）+ 违约记录 Tab（迁移 ViolationListView 表格）
- [ ] **步骤 9.4 验证**：build 全绿；截图对照稿 08（周网格/右栏两区域裁剪）；回归——通过/拒绝全链路、周切换与资源筛选、违约 Tab、`?tab=` 深链
- [ ] **步骤 9.5 Commit**：`feat(beautify-admin): 预约管理——周视图网格+待审核右栏+违约Tab（数据预检结论:支持/降级）`

### 任务 10：房源管理（卡片网格 + 看房预约 Tab + 详情下钻美化）

**文件：** 创建 `housing/HousingManageView.vue`；改造 `HousingListView.vue`、`ViewingAppointmentListView.vue`、`HousingDetailView.vue`

- [ ] **步骤 10.1 对齐现状**：清点保留清单（Housing CRUD 上下架、时段管理、看房预约确认/拒绝/违约动作）；**字段漂移全量对齐**（HousingVO 真实字段 houseLocation/deposit/layout/rentType/publishTime，读取侧切真实字段——游客端已修同款，管理端此任务收口）
- [ ] **步骤 10.2 实现**：4 AdminStatCard（在租/待租/已租/本月看房——状态计数来自列表接口）+ 2 Tab（房源/看房预约）；房源 Tab = 摄影卡片网格（封面图 uploads/占位渐变块、状态标签 on-image 实色变体——居民端已沉淀的 StatusTag on-image 能力复用、价格/面积/朝向行、编辑/时段/下架操作）；卡片数据用现有列表接口 size 加大分页
- [ ] **步骤 10.3 详情下钻美化**：房源详情换壳（图集 + 信息 + 时段管理 + 预约列表），保留全部绑定
- [ ] **步骤 10.4 验证**：build 全绿；截图对照稿 09（卡片网格含亮/暗封面各一）；回归——上下架、时段编辑、看房预约确认/拒绝、`?tab=` 深链、详情跳转
- [ ] **步骤 10.5 Commit**：`feat(beautify-admin): 房源管理——摄影卡片网格+看房预约Tab（HousingVO漂移收口）`

### 任务 11：评价管理（统计头 + Tab + 跟进抽屉，删子路由）

**文件：** 创建 `evaluation/EvaluationManageView.vue` + `evaluation/FollowupDrawer.vue`；改造 `EvaluationListView.vue`；删除 `EvaluationFollowupView.vue`（内容迁入）

- [ ] **步骤 11.1 对齐现状**：清点保留清单（listEvaluations/listUnsatisfiedEvaluations/addEvaluationFollowup/listEvaluationFollowups、评分维度展示、已知双发请求 bug 顺修——watch(filter) 与 watch([page,size]) 叠加）
- [ ] **步骤 11.2 实现**：统计头两卡（综合评分大数字+星级 / 五档评分分布横条——由列表 total+rating 聚合或专用统计接口，无接口则首页全量聚合并记录）+ 白卡 Tab（全部/待跟进/已跟进）+ 评价卡片列表（头像首字+姓名、星级、内容、工单号+日期、状态胶囊、低分未跟进卡显「发起跟进」主按钮）；发起跟进 → FollowupDrawer（跟进表单 + 历史跟进时间线，复用 EvaluationFollowupView 现有逻辑）
- [ ] **步骤 11.3 验证**：build 全绿；截图对照稿 10；回归——发起跟进提交、时间线展示、Tab 过滤、旧路由 redirect、分页修复验证
- [ ] **步骤 11.4 Commit**：`feat(beautify-admin): 评价管理——评分统计头+跟进抽屉（删子路由，顺修双发请求）`

### 任务 12：系统管理（3 Tab + 绑定抽屉，删子路由）

**文件：** 创建 `auth/SystemManageView.vue` + `auth/SysUserCommunityDrawer.vue`；改造 `SysUserListView.vue`、`OperationLogListView.vue`、`GlobalConfigView.vue`

- [ ] **步骤 12.1 对齐现状**：清点保留清单（SysUser CRUD/重置密码/禁用、社区绑定管理、操作日志筛选、全局配置键值编辑含 log.retention_days 正整数校验——50 阶段 1b 刚交付的能力必须原样保留）
- [ ] **步骤 12.2 实现**：白卡 3 Tab（系统用户/全局配置/操作日志，`?tab=` 契约，稿 11 已明示）；用户 Tab = 角色筛选 + 表格（头像首字+用户名/姓名/角色胶囊/绑定社区列（点击开抽屉）/状态/最近登录/操作）；绑定抽屉 = SysUserCommunityView 内容迁移（绑定/解绑 + 二次确认）；ADMIN 角色访问 `/admin/system` 时用户与配置 Tab 显「仅超级管理员可见」空态（el-empty），操作日志 Tab 正常
- [ ] **步骤 12.3 验证**：build 全绿；截图对照稿 11（superadmin 视角）；ADMIN 账号截图确认空态；回归——用户 CRUD/重置密码/禁用、绑定抽屉增解绑、日志筛选翻页、log.retention_days 编辑校验（输入 0 拒绝，50-1b 冒烟口径）、`?tab=` 深链
- [ ] **步骤 12.4 Commit**：`feat(beautify-admin): 系统管理——用户/配置/日志三Tab+绑定抽屉（保留1b交付的留存配置能力）`

### 任务 13：登录/注册/403/404 设计稿生成（imagegen + style-library）

**文件：** 创建 `design-mockups/auth/01-登录.png`、`02-注册.png`、`03-403-404.png`；更新 `design-mockups/_manifest.json`

- [ ] **步骤 13.1 选风格**：读 `gpt-image-2-style-library` skill 的 `references/style-library.md`，按「全站设计语言：米白暖灰底 + 品牌蓝 #3b6dff + 白卡柔阴影 + 真实摄影图」匹配 UI/登录场景模板；三条提示词按六块结构（主体/构图/风格/文字/比例/约束）用中文写好，文字区只写布局与语气（AI 乱码文字不进代码，mockup-to-page 纪律）
- [ ] **步骤 13.2 生成**（imagegen skill，不加 `--channel/--model` 让脚本自动回退）：
  ```bash
  py D:/videogame/imagegen/gen.py "<登录页提示词：桌面 4:3 或 16:10 竖向居中白卡左右分栏（左表单右摄影图），品牌蓝主按钮，米白暖灰底 #f7f5f2，中文界面标签布局>" -o design-mockups/auth/01-登录.png --size 1536x1024
  py D:/videogame/imagegen/gen.py "<注册页提示词：同风格延续，步骤感表单分组>" -o design-mockups/auth/02-注册.png --size 1536x1024
  py D:/videogame/imagegen/gen.py "<403/404 提示词：大数字状态码+插画+返回首页按钮，同色系>" -o design-mockups/auth/03-403-404.png --size 1536x1024
  ```
- [ ] **步骤 13.3 校验**：逐张 Read 检查比例/主体完整/无关键乱码；不合格的按 imagegen 故障策略重试（全渠道失败等 10~30 分钟，不写循环脚本）；manifest 衬记三张稿的用途与对应路由
- [ ] **步骤 13.4 Commit**：`feat(beautify): 登录/注册/403/404 设计稿三张（imagegen+style-library）`

### 任务 14：登录页 + 注册页美化

**文件：** 改造 `views/auth/LoginView.vue`、`views/auth/RegisterView.vue`

- [ ] **步骤 14.1 对齐现状**：清点保留清单（登录表单校验/提交/loading/错误提示、注册表单全字段与社区选择、注册方式开关 sys_config 逻辑、跳转逻辑按角色路由、`?redirect=` 回跳）
- [ ] **步骤 14.2 实现**（对照稿 01/02，mockup-to-page 五步法完整走）：左右分栏白卡（左品牌+表单、右摄影图区——若稿为纯表单居中则从稿）；摄影图素材按 imagegen 比例契约生成 1 张（3:2，`public/images/auth-hero.jpg`，提示词写明比例与安全边距）；表单控件换自写样式（品牌蓝主按钮/浮动标签或简洁标签，遵守「自写为主」组件策略）；403/404 同批换壳见任务 15
- [ ] **步骤 14.3 验证**：build 全绿；截图对照稿（1536x1024 + 移动端 390x844 两档——登录页移动端必须单栏）；回归——错误密码提示、五角色登录后各自落位、注册开关关闭态、`?redirect=`
- [ ] **步骤 14.4 Commit**：`feat(beautify): 登录/注册页按稿重构（保留全部认证逻辑）`

### 任务 15：403/404 页美化

**文件：** 改造 `views/ForbiddenView.vue`、`views/NotFoundView.vue`

- [ ] **步骤 15.1 实现**：对照稿 03——大号状态码数字 + 插画/图标 + 说明文案 + 「返回首页/返回上一页」双按钮；布局与登录页同色系；保留现有 `router.meta` 与守卫跳转逻辑不动
- [ ] **步骤 15.2 验证**：build 全绿；截图两张（/403 与任意不存在路径）；回归——登出后访问受限页落 403、404 返回首页
- [ ] **步骤 15.3 Commit**：`feat(beautify): 403/404 页按稿重构`

### 任务 16：全站回归 + 文档回写（收尾）

**文件：** 修改 `BEAUTIFY_NOTES.md`；全站截图

- [ ] **步骤 16.1 全站回归**：`npm run build` 全绿；四端各截关键页一组（guest 首页/resident 首页/staff 工作台/admin 11 页）核对 D1 底色切换后无布局劣化；既有交互回归脚本（staff 三脚本 + admin 新脚本）全跑一遍全绿
- [ ] **步骤 16.2 文档回写**：BEAUTIFY_NOTES 更新——管理端 11 类完成状态、每页数据决策汇总（MOCK/省略/近似清单）、D1/D2/D3 裁决结果、遗留差异项；README.md「怎么跑」节前端描述若受 beautify 影响则同步一句（遵守多会话小块编辑纪律）
- [ ] **步骤 16.3 Commit**：`docs(beautify): 管理端+auth 美化收尾——回归记录与数据决策回写`

---

## 自检记录（writing-plans 三项检查）

1. **规格覆盖度**：11 张设计稿 ↔ 任务 2~12 一一对应；登录/注册/403/404 ↔ 任务 13~15；用户关切「11 页是否够/要不要子页」↔ §二裁决表逐类给出；已知 bug（死筛选/分页未挂/双发请求/重置不重载）分散在对应任务顺修；接口漂移（公告 D2/预约预检/HousingVO）各有归属任务与降级路径；**无遗漏**。
2. **占位符扫描**：全文无「待定/TODO/后续实现」；步骤均为可执行动作+命令+预期；唯一开放点（预约周视图参数、D1/D2/D3 裁决）已显式写成「预检步骤 + 降级方案」而非悬空。
3. **类型一致性**：路由名（AdminDashboard/AdminCommunity/…）在任务 1 定义、任务 2~12 引用一致；`?tab=` 取值在各任务内自洽（community: tree|houses|resources；residents: list|applications|relations——**实施注意**：任务 1 redirect 写 `?tab=applications|relations`，则 ResidentManageView Tab 名必须同为 applications/relations，与居民列表 Tab 的 `list` 区分）；api 函数名均来自现有 `api/*.ts` 实际导出（listReservations/listAvailableTimeslots/addEvaluationFollowup 等已核实）。
