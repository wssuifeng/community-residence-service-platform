# 前端美化工作区笔记（frontend-beautify 分支）

> 本文件不属于 docs/ 文档体系，仅记录本工作区的优化观察与待办。
> **注意：本工作区有两个并行会话在改代码（游客/居民端会话 + 服务人员端试点会话），
> 改共享文件（variables.css、AppHeader、StatusTag、Pagination、共享组件）前先
> git diff 确认对方最新状态，token 只增量不改动既有值。**

## 进度快照（2026-09-11，供上下文回收后续接）

**工作方式**：skill `mockup-to-page`（C:/Users/17841/.agents/skills/）五步法；
设计稿 `design-mockups/{guest,resident,staff,admin}/`（guest 7 / resident 11 /
staff 3 / admin 11）；验收截图 `frontend/acceptance-shots/`；生图走 imagegen
skill（nccnxta 渠道 gpt-image-2-sx 优先，furry 备用）。

**已完成（游客/居民端会话）**：
- 游客端全部 4 页 + GuestLayout 深色页脚 + AppLogo（双屋 SVG currentColor）+
  favicon + AppHeader 头像对齐 + Pagination 居中（修复双 emit）+
  StatusTag on-image 实色变体
- 居民端全部 11 页：首页 / 我的工单 / 工单详情（五步进度条+双态切换+底部操作条）/
  我的反馈+详情（左会话右信息）/ 我的预约（日历标记+日期筛选）/
  消息中心（分类列+加载更多，已统一进三端共享组件）/ 房源列表+详情（镜像游客端）/
  公告列表+详情（2026-09-11 按用户参考截图重做：置顶横拉卡条+左右悬浮箭头、
  日期块期刊式列表（大号日+年月+竖线间隔+阅读数）、方块分页（Pagination 新增
  可选 layout prop，默认不变）、详情页容器 760→1000px+置顶红标+衬线标题+元信息行
  +通栏圆角封面 21/9+上一篇/下一篇收进同容器底部；游客/居民四文件同步）/
  提交工单 / 提交反馈

**已完成（服务人员端试点会话）**：工作台 `/staff/dashboard`、工单列表
`/staff/work-orders`、工单详情 `/staff/work-orders/:id`（见下「已落地页面」，含
MOCK 占位约定与 7 项待补后端接口清单）

**已完成（本会话追加，2026-09-12 凌晨）**：登录 `/auth/login` / 注册
`/auth/register` / 403 / 404 四页。用户指派本会话做；先生成设计稿
`design-mockups/auth/`（4 张定稿：登录注册左右分屏同体系、错误页内联精简导航+
居中插画）再落地。素材：登录品牌图 `login-brand-panel.png`（1024x1536 暮色社区
摄影，新生成）、注册复用 `guest-hero.png`、403/404 插画从设计稿裁剪为
`error-403.png`/`error-404.png`。功能全保留：登录双 tab 分接口+redirect 回跳+
按角色跳首页；注册 7 字段全部前端校验；403 保留按角色回跳、新增「重新登录」；
404 保留返回首页、新增「查看社区公告」。验收截图 `frontend/acceptance-shots/
{auth-login-v1,auth-login-v1-tab-admin,auth-register-v1,forbidden-v1,notfound-v1}.png`，
build 全绿、交互回归全 PASS（403/404 未登录时回首页均落 /guest/home，符合既有逻辑）。

**未开始**：管理端全部 11 类（设计稿齐）。
服务人员端消息中心 `/staff/notifications` 复用共享组件 `NotificationList`，
已随居民端会话升级达标（对照 `staff/04-消息中心.png` 结构一致），无需单独重构。

**服务进程**：前端 5273（Vite）、后端 8080（Spring Boot；DB 密码见
docs/90_全局/决策日志.md，明文已泄露在 git，用户已知情待轮换）。
2026-09-11 曾因机器内存耗尽重启过（子代理排查：后台 3.8GB 游戏进程挤占）。
2026-09-12 主会话以 disable_timeout 重启双进程（此前后台任务 1h 超时被杀过一次）。

**生图渠道**：nccnxta 渠道 2026-09-12 起额度不足（剩 $0.02，单张需 $0.032），
gen.py 会自动回落 furry 渠道（gpt-image-2），本轮 5 张图均走 furry 成功。

**全站待裁决**：`--color-bg` 现为 #f9fafb 冷灰，定稿方向是米白暖灰 #f7f5f2——
未擅动，待用户全站决策。

**管理端会话（2026-09-12，收尾状态）**：
- 管理端 11 类 + 登录/注册/403/404 美化**全部完成**，`ADMIN_BEAUTIFY_PLAN.md` 16 任务闭环
  （任务 13 变体核验、14/15 裁决跳过；执行状态表见根目录 `ADMIN_BEAUTIFY_PLAN.md`）
- **协作事实**：auth 四页与 4 张设计稿由游客/居民端会话于 2026-09-12 凌晨交付（见上方
  进度快照「本会话追加」），本计划裁决任务 14/15 跳过重做避免覆盖其成果，核验并入任务 16
- 架构裁决（已落地）：11 侧栏项 + 4 隐藏下钻详情路由；6 类页内 Tab、2 类主从双栏；
  7 个子路由删除改弹层/并入（详见计划 §二）
- **收尾回归（任务 16，2026-09-12）**：`npm run build` 全绿；管理端 13 个验收脚本
  770 断言 PASS + 13 断言 FAIL——13 项全部定因为验收脚本资产滞后/验收数据累积漂移
  （shell 6 条占位断言、feedback 5 条基线计数、workorder/evaluation 各 1 条数据定位断言），
  非产品缺陷，逐条根因与探针证据见
  `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/task-16-regression.md`；服务人员端三回归脚本
  全 PASS；auth 四页核验 + 游客/居民端抽查 ALL PASS；全链控制台零报错
- 遗留 Minor 全清单见下方「管理端遗留 Minor 清单（延后项，2026-09-12）」节，
  供用户整轮审查时逐条裁定
- D3 纪律：本轮全程未做任何 git 提交，截图/脚本/代码改动全留工作树待用户审查

**验收修复第二轮（2026-09-12，8 任务 R1~A8 全部闭环）**：
- 用户验收 11 项反馈按根目录 `ROUND2_FIX_PLAN.md` 修复完毕（R1 通知三修 / R2 重复
  定性+残留清理+去重兜底 / R3 居民预约三段流 / A4 看板三修 / A5 头部右对齐 /
  A6 预约管理月历改版 / A7 社区结构增强 / A8 落档回写）——全部 build 全绿 +
  Playwright 回归通过 + 控制台零报错，**后端零改动**；任务级结论见
  `ROUND2_FIX_PLAN.md`「执行状态（2026-09-12）」表
- 本轮裁决与缺口落档：大表单三原则见下方「设计约定（2026-09-12 验收后裁决）」节；
  接口能力缺口见「后端适配清单」R2 轮条目（含 R1/R2/R3 三项核查无缺口注）；
  残留数据变动见「验收数据残留汇总」2026-09-12 标注与追加行

**验收修复第三轮（2026-09-12 验收修复第三轮，4 任务 C1~C4 全部闭环）**：
- C1 登录/注册 autofill 全局修复：`styles/index.css` 新增全局 `input:-webkit-autofill`
  规则（text-fill-color/caret-color 统一主文色 + 1000px 输入框底色内阴影 +
  长过渡顶掉 Chrome 强制底色），真实效果待用户 Chrome 目检
- C2 消息中心入管理端侧栏（12 项，「评价」后「系统」前，无角色限制）+
  看板「查看全部」统一落 `/admin/notifications`（动态行逐项类型跳转不变）
- C3 整栋创建：楼 + 单元 + 房屋一体三分区对话框（BuildingBatchDialog 改名
  BuildingCreateDialog，全失败保留输入——顺修 A7 遗留 Minor 50）+
  批量建单元前缀自定义（默认取所在楼栋名，可编辑可清空）
- C4 结构总览房屋网格可视化增删：网格卡片悬浮编辑/删除快捷钮 + 单元区块标题「＋」
  快速添加（新建 HouseEditDialog，HouseListView 表格形态按用户原话保留不动）
- 验证：build 全绿 + Playwright 20/20、31/31、35/35 PASS（C4 另回归上轮 C3
  脚本 ALL PASS）+ 控制台零报错；C3/C4 验收数据自清理零残留；报告
  round3-task-C12/C3/C4-report.md；本轮新延后项见「管理端遗留 Minor 清单」
  「R3 轮遗留」小节；交接文档见根目录 `HANDOVER.md`

**验收修复第四轮（2026-09-12 验收修复第四轮，3 任务 D1~D3 全部闭环）**：
- D1 autofill 修复 v2：无限过渡方案替换内阴影——v1 巨大 spread 内阴影溢出原生
  input 可见区、把输入框边框与圆角也铺白（用户「边框缺一块」），v1 废弃并在样式
  注释标注勿改回；正常态像素级零差异，真实效果待用户 Chrome 目检 —
  round4-task-D1-report.md
- D2 楼栋级联删除编排：空楼栋直删口径澄清（后端本就允许删，原确认文案误导）
  + 非空楼栋自底向上级联删除（范围确认 → 编排进度 → 中断剩余明细汇报，
  非事务明示「已删除部分不会回滚」） — round4-task-D2-report.md
- D3 房屋网格 Ctrl/Cmd+点选多选 + 空白区橡皮筋框选 + 批量删除操作条
  （useGridSelection composable 容器作用域一次消费覆盖单元/楼栋双挂载复用） —
  round4-task-D3-report.md
- 验证：build 全绿 + Playwright 7/7、27/27、37/37 断言 PASS（D2/D3 连跑两轮）
  + 控制台零报错；D2 已记「后端适配清单」「楼栋删除事务级联（R4-D2）」条目；
  本轮新延后项见「管理端遗留 Minor 清单」「R4 轮遗留」小节

## 设计方向定稿（2026-09-10 用户确认）

- 全站设计语言：米白暖灰底 #f7f5f2 + 品牌蓝 #3b6dff + 白卡柔和染色阴影 + 真实摄影图
- 游客端首页采用 **沉浸式方案**（design-mockups/guest/01-首页-沉浸式-定稿.png：
  全屏摄影 Hero + 毛玻璃导航 + 搜索框 + 叠层服务卡 + 深色页脚）
- 其余三端采用第一版设计稿（resident/01、staff/01、admin/01 风格）
- 管理端按「一类一页」重构：看板 / 社区结构 / 工单 / 居民 / 租住 / 公告 / 反馈 /
  预约 / 房源 / 评价 / 系统（用户+配置+日志三合一），消灭子页面海
- 设计稿全集：design-mockups/{guest,resident,staff,admin,auth}/（35 张，按端分文件夹：guest 7 / resident 9 / staff 4 / 管理端 11 / auth 4）
- 已知不一致：admin/06-公告管理 的侧栏菜单是 AI 即兴发挥的，实现时以
  admin/01、admin/02 的菜单体系为准
- 素材待生成：hero 摄影图、房源示例图 4~6 张（3:2）、公告分类封面 3~4 张

## 已落地页面（mockup-to-page 五步法）

- [x] **服务人员端工作台 `/staff/dashboard`**（2026-09-11，试点页：先做一个界面看 skill 执行效果）
  - 对照稿 `design-mockups/staff/01-工作台.png`；证据截图
    `frontend/acceptance-shots/staff-dashboard-{1536x1024,full,urgent-1536x1024}.png`
    + 3 张区域裁剪
  - 结构改造：加入问候条 / 区块标题「今日概览」/ 第 4 张「紧急待接单」统计卡 /
    紧急告警条 / 待办行改「竖排优先级色块 + 独立白卡」（原先是一张大卡 + el-table + 分割线）/
    右栏「今日完成进度」环形图 + 「最新消息」面板；两栏 `1.9fr : 1fr`，≤991px 落单栏
  - 保留清单（已逐项回归）：4 路 `listWorkOrders` 统计、`goList(tab)`、`goDetail`、
    `StatusTag` 语义映射、`el-empty` 空态、`v-loading`、`ElMessage` 错误提示、
    原表格 7 列信息全部保留为行内元素
  - 数据决策（**2026-09-11 按用户指示改为「造数据 + 显式标识」**，缺的接口见下方清单）：
    - **真实数据**：四张卡数值、待办列表、最新消息、紧急告警条全部走真实接口
      （紧急走 `priority=URGENT`，已核 `WorkOrderController:84` + `WorkOrderService:183`）
    - **MOCK 占位**（代码中集中在 `【MOCK 临时占位区块】`，全局检索 `MOCK` 可定位并整块删除）：
      统计卡趋势百分比与迷你趋势图、居民性别称谓（先生/女士）、居住身份（业主/租客/家属）
    - **近似**：头像用姓名首字字符画（后端无头像字段）
  - 顺带修字段漂移：原表格列 `orderNumber` 后端实际为 `orderNo`（工单号列原显示空值）
  - 新增 token（仅增量，未改既有值）：`--color-danger-soft` / `--color-warning-soft` /
    `--color-success-soft` / `--shadow-card`
  - 遗留 P2：全站 `--color-bg` 仍是 `#f9fafb`（冷灰），与设计方向定稿记的
    米白暖灰 `#f7f5f2` 不一致——属全站决策，未擅动
  - 用户 2026-09-11 第 2 轮反馈（6 点）已全部落实：概览标题并入大白容器 / 卡片改为
    「左上数据名 + 数值 + 带色趋势指数 + 右侧趋势图」/ 大白底与浅灰大背景分层 /
    待办为大白容器且标题左上「全部」右上 / 工单卡左竖状态条 + 标题 + 头像与
    「姓+性别」及身份 / 右栏两容器与左栏等高（实测高度差 0.0px）
  - 趋势配色按用户口径：**上升红、下降绿**（纯方向，非"好坏"语义）

- [x] **服务人员端工单列表 `/staff/work-orders`**（2026-09-11，对照稿 `design-mockups/staff/02-工单列表.png`）
  - 证据截图 `frontend/acceptance-shots/staff-work-orders-{1536x1024,full,mocked-1536x1024}.png`
    + 3 张区域裁剪（页头/Tab/卡片、紧急卡）
  - 结构改造：页头大标题 + 搜索框；`el-tabs` → 白卡 Tab 条（4 个 Tab 全部带角标）；
    `el-table` 8 列表 → 工单卡片列表（左侧优先级色条 + 优先级胶囊 + 头像与
    「姓+性别 / 身份」+ 标题与状态标签 + 工单号·分类·地址·提交时间 + 右侧按钮）
  - **Tab 口径统一**：原先「处理中」含 `TO_CONFIRM`，与工作台统计（`ACCEPTED+IN_PROGRESS`）
    不一致；现按设计稿拆成 4 个 Tab（待接单/处理中/待确认/已完成），
    `?tab=todo|doing|done` 契约保留并新增 `confirm`，工作台 4 张卡的跳转已回归通过
  - **分页口径**：原先多状态 Tab 是「各状态各取一页再合并 slice」，跨页会漏/重；
    现改为各状态取满 100 条后页内合并分页（实测 15 条 → 2 页，跨页无重无漏）。
    限制：单状态超过 100 条时只呈现最新一批，待后端支持多状态查询后恢复服务端分页
  - 搜索：接后端 `keyword`（实测标题片段命中 2 条）；**工单号不参与匹配**
    （`WorkOrderService:185-187` 只 like title/content），故占位文案按真实能力写
    「搜索工单标题/内容…」，不照抄设计稿的「搜索工单号/标题」——见待补接口 ⑦
  - 保留清单（已逐项回归）：`listWorkOrders` 五路调用与 `Promise.all`、Tab 状态集、
    `goDetail`、`StatusTag` 语义映射、`Pagination` 共享分页条、`el-empty` 空态、
    `v-loading`、`ElMessage` 错误提示、原表格 7 列信息全部保留为行内元素
  - 交互回归 6/6：Tab 切换、`?tab=` 直达、搜索命中、搜索空态、卡片点击进详情、
    行内按钮 `@click.stop` 进详情；控制台零错误

- [x] **服务人员端工单详情 `/staff/work-orders/:id`**（2026-09-11，对照稿 `design-mockups/staff/03-工单详情.png`）
  - 证据截图 `frontend/acceptance-shots/staff-order-detail-{1536x1024,mocked-1536x1024,full}.png`
    + 6 张区域裁剪（状态条/工单信息/时间线/操作面板 × 不同状态）
  - 结构改造：面包屑（房子图标 + 工单列表 / 工单号）；新增顶部状态条白卡
    （优先级胶囊 + 大标题 + 状态标签）；主区改三栏 `1.05fr : 1fr : 1fr`
    ——工单信息（头像与「姓+性别 / 身份」+ 地址·分类·联系电话·提交时间 + 描述 +
    现场照片 + 附件）/ 处理时间线（自绘节点，灰点=历史、蓝勾=当前）/
    操作面板（主按钮 + 联系业主 `tel:` + 处理结果说明 + 处理备注 + 处理后照片 +
    申请改派占位）；三栏实测等高（685/685/685，极差 0px）
  - **顺带补上遗漏字段**：原页面没展示 `address`（后端 `WorkOrderVO` 早已返回）
  - **修一处数据丢失缺陷**：后端 `complete` 读的是 `WorkOrderActionDTO.remark`
    （`@NotBlank` + `max 500`），接口文档里的 `solution` 字段根本没被读取——
    原实现只传 `solution`，结果说明被丢弃；只填结果说明不填备注还会因 `@NotBlank`
    直接 400。现改为把处理结果说明写进 `remark`（备注追加其后），两个字段同时携带；
    实测请求体 `{"solution":"…","remark":"…（备注：…）"}`，并把两个输入框上限
    对齐后端（结果说明 500 / 备注 200，合计超 500 前端拦截）
  - 保留清单（已逐项回归）：`getWorkOrder`+`getWorkOrderTimeline`+`listWorkOrderAttachments`
    的 `Promise.all`、`accept/process/complete` 三个动作与二次确认、
    `ImageUploader` 六张上限与 `attachImages` 回捞转存、`el-image` 预览画廊、
    文档附件列表、`el-empty` 空时间线、`v-loading`、`ElMessage` 错误提示
  - 状态覆盖回归：已派单（接单）/已接单（开始处理）/处理中（结果表单+提交）/
    待确认（无操作说明）/空时间线，5 种状态全部实测；控制台零错误
  - 入口迁移：原页头的「返回列表」按钮删除，入口由面包屑「工单列表」承担（同一入口）

- [x] **MOCK 占位收敛**：新增 `frontend/src/utils/staffPlaceholder.ts`，把
  「姓+性别称谓」「居住身份」「头像首字」三个 MOCK 取值集中到一处，
  工作台/列表/详情三页共用（按工单 ID 取模，同一工单三页显示一致，
  实测同一工单 #10 在列表与详情均为「演先生 / 租客」）。工作台改为 import 后
  MOCK ①②③ 只需删这一个文件；工作台既有 8 项交互回归全部通过

### 设计稿缺口的大致模型（用户 2026-09-11 询问，仅需归类不需逐页补）

| 模型 | 覆盖缺页 | 已有可复用范式 |
|---|---|---|
| 列表页变体 | 居民端：房源列表、公告列表、看房预约列表 | 有（工单列表 / 消息中心） |
| 详情页变体 | 游客端：公告详情；居民端：房源详情、公告详情 | 有（房源详情 / 工单详情） |
| 表单页 | 居民端：预约创建、工单创建、反馈创建 | 半有（仅反馈会话可参考，表单本身无稿） |
| 个人资料页 | 居民端：个人资料 | 无，需单独设计 |
| 管理端归并 | 管理端全部 | 稿已齐（11 张），缺的是 33 散页 → 11 类页的合并 |

即真正需要新设计的只有**表单页**与**个人资料页**两类，其余套已有范式换业务字段即可。

## 已知问题

### 待补后端接口（2026-09-11，服务人员端工作台；前端已用 MOCK 占位并显式标识）

前端代码里搜 `MOCK` 可定位占位区块，接口就绪后整块删除改接真实数据。

- [ ] **① 服务人员端趋势统计**：需要 STAFF 可访问的按日聚合历史快照接口，
      例如 `GET /api/v1/statistics/staff/trend?days=7`（返回各指标的日序列 + 环比）。
      现状：`StatisticsController` 全部端点均为 `hasAnyRole('ADMIN','SUPER_ADMIN')`
      （第 27/34/41/48/55 行），STAFF 取不到 → 统计卡趋势百分比与迷你趋势图为 MOCK。
- [ ] **② 工单提交人性别**：`WorkOrderVO` 需增补 `gender` 字段。
      现状：全后端无 gender（`ResidentVO`、`Resident` 实体均无）→ 前端「张先生/张女士」
      组合名为 MOCK（按工单 ID 取模，保证渲染稳定不跳动）。
- [ ] **③ 工单提交人居住身份**：`WorkOrderVO` 需增补 `relationType`（OWNER/TENANT/FAMILY）。
      现状：数据在 `RelationVO.relationType`，但 `ResidenceRelationController.java:31`
      白名单为 RESIDENT/ADMIN/SUPER_ADMIN，**不含 STAFF**，服务人员端查不到
      → 前端身份标签为 MOCK。
- [ ] **④ 工单列表时间范围过滤**：`GET /work-orders` 需支持 `startTime`/`endTime`。
      现状：`WorkOrderService.page()` 只接 status/priority/categoryId/keyword
      → 统计卡「今日完成」实际取的是**累计完成**（`COMPLETED` 总数），
      名称与口径不符，待参数补齐后修正为真正的"今日"。
- [ ] **⑤ STAFF 申请改派接口**：服务人员端需要可提交的改派端点
      （现状 `PATCH /work-orders/{id}/assign` 仅 `ADMIN/SUPER_ADMIN`，
      `WorkOrderController.java:91`；STAFF 侧无任何改派入口）。
      设计稿 `staff/03-工单详情.png` 右栏有「申请改派」按钮 → 前端已按设计稿
      呈现按钮，点击提示待补（**MOCK 区块**，检索 `notifyReassignPending`），
      接口就绪后替换为真实提交。
- [ ] **⑥ 工单 SLA/时限字段**：设计稿 `staff/02`、`staff/03` 都有
      「SLA 剩余 1小时20分」红色倒计时，需 `WorkOrder` 增派单时间或
      承诺时限字段（如 `assignTime` + `slaDeadline`）。
      现状：`work_order` 表无 SLA/时限字段（`WorkOrder` 实体 13 个字段，
      无 assignTime/deadline）→ 前端**省略**该元素，其信息位置由
      优先级胶囊 + 提交时间承担。
- [ ] **⑦ 工单关键字搜索覆盖工单号**：设计稿搜索占位为「搜索工单号/标题…」，
      但 `WorkOrderService.page():185-187` 的 `keyword` 只 like `title/content`
      → 前端占位文案按真实能力改为「搜索工单标题/内容…」；
      需后端把 `orderNo` 纳入 keyword 匹配后再改回设计稿文案。
- [ ] **⑧ 工单列表服务端多状态分页**：`GET /work-orders` 的 `status` 只接受单值，
      多状态 Tab（处理中=已接单+处理中）只能各状态取满 100 条后页内分页
      → 单状态超过 100 条时会丢掉较早的记录。需后端支持 `status` 传数组
      （或复用 `statuses=a,b`），前端即可恢复服务端分页。

- [ ] **接口字段漂移（第四批，NoticeVO，2026-09-11 公告重做发现）**：
  - 后端 NoticeVO 实际仅返回 id/communityId/communityName/title/content/status/
    publishTime/endTime/viewCount/publisherId/publisherName/createdAt——前端类型的
    `priority`/`type`/`expireTime` 均为漂移（types/modules/notice.ts 已改可选 +
    追加 endTime/pinned/isPinned 可选；有效期展示改 `endTime ?? expireTime`）
  - **`notice` 表有 `is_pinned` 列，但后端代码全库无任何引用、VO 未暴露**——
    公告置顶横拉条已按防御式实现（priority 枚举 OR pinned 布尔），VO 暴露
    `is_pinned` 后自动点亮；当前真实数据下置顶条不渲染（视觉经 Playwright
    路由注入 pinned:true 验证通过）。需后端补 VO 字段（归主工作区裁决）
  - 管理端公告创建/编辑表单仍按旧契约提交 priority/type/expireTime，与后端
    已脱节，管理端公告模块需与后端统一裁决
- [ ] **接口字段漂移（第三批，通知/反馈状态，2026-09-11 发现）**：
  - 通知列表接口仅支持 `page/size/isRead`，前端类型的 `sourceType` 参数
    为文档漂移——消息中心分类筛选暂为前端过滤（加载更多累加模式），
    根治需后端支持 sourceType
  - 反馈状态枚举：后端真实为 `PENDING/IN_SESSION/CLOSED`，前端类型写成
    `OPEN/IN_PROGRESS/CLOSED`——**已修齐**（resident/admin 反馈页）
- [ ] **接口字段漂移（第二批，预约/工单时间线，2026-09-11 居民端美化发现）**：
  - 预约单：后端实际返回 `reserveDate`/`userId`/`userName`，类型里是
    `reservationDate` 等——本工作区只修了居民首页待办读取侧，
    `resident/ReservationListView` 等仍在用漂移字段会显示异常
  - 工单时间线：后端实际返回 `newStatus`/`content`（类型是 `status`/`remark`）
    ——**已三端修齐**（resident/staff/admin 工单详情页）
  - 连同 HousingVO 漂移，建议统一裁决后全量对齐（归主工作区）
- [ ] **接口字段漂移（HousingVO）**：前端 `types/modules/housing.ts` 中
      `houseAddress`/`depositAmount`/`availableDate`/`contactPerson`/`contactPhone`
      在后端实际响应中不存在，真实字段为 `houseLocation`/`deposit`/`layout`/
      `rentType`/`publishTime`。美化工作区只修了游客端读取侧（类型里追加真实
      字段，旧字段保留并注释漂移说明）；**管理端/居民端房源页仍在用漂移字段，
      会显示空值**——需裁决口径后全量对齐（涉及接口契约，归主工作区处理）
- [x] ~~Pagination 重复 emit 翻页发两次请求~~ **已修复**（2026-09-10，
      `components/common/Pagination.vue` 删除冗余 `@current-change` 监听，
      页码只走 v-model setter 单通道；该 bug 此前还导致翻页被抢回第 1 页）
- [ ] **前端搜索栏其余问题**（2026-09-10 用户反馈）。代码审计已定位具体 bug：
  1. ~~【真 bug】Pagination 双 emit~~（见上，已修）
  2. 【真 bug】`admin/workorder/WorkOrderListView.vue:45,154-162`：日期筛选是死控件，
     `fetchList()` 从不发送 startTime/endTime
  3. 【真 bug】`admin/workorder/WorkOrderListView.vue:212`、
     `admin/evaluation/EvaluationFollowupView.vue:160`：Pagination 没挂处理函数，
     翻页/改页大小不发请求
  4. 【双发请求】`admin/evaluation/EvaluationListView.vue:54-61`：watch(filter) 与
     watch([page,size]) 叠加，page≠1 时切筛选发两次
  5. 【交互不一致】SearchBar `@clear` 立即搜索但输入需回车；回车挂外层 div 导致
     中文输入法候选词确认也触发搜索；`ResidenceRelationListView.vue:87-93` 重置后不重新加载
  6. 【能力不一致】Unit/House/PublicResource 三页无搜索框，与 Building 页不一致
- [ ] **需后端房源列表接口支持户型/朝向过滤参数**（2026-09-11 游客端美化）：
      列表接口当前仅 `communityId/status/minRent/maxRent/keyword`，用户要求
      的户型（后端 HousingVO 有 `layout` 字段可过滤）与朝向筛选暂无参数支撑，
      前端按"不做假控件"原则未实现，待后端补参数后接入
- [x] ~~游客端社区筛选下拉~~ **已落地**（2026-09-11）：核查确认
      `GET /api/v1/communities` 无 `@PreAuthorize`、游客可匿名访问，房源列表
      筛选条已接社区下拉（选中带 `communityId` 重查）

### 后端适配清单（管理端美化发现，待后端主工作区排期）

- [ ] **公告表单漂移字段**：保存公告接口待接收 `priority` / `type` / `expireTime`
      （前端表单维持提交）；NoticeVO 待暴露 `is_pinned`（前端置顶条已防御式实现，
      字段一到自动点亮）
- [ ] **公告列表过滤（任务 7）**：`GET /notices` 缺 `priority` 过滤参数——前端死筛选控件
      已按「不做假控件」原则移除，参数就绪后可回补
- [ ] **工单 ADMIN 派单链路（任务 4）**：`GET /api/v1/sys-users` 为 SUPER_ADMIN 专属
      （`SysUserController.java:62`），ADMIN 打开派单对话框 403、人员下拉为空，
      社区管理员无法派单——需 ADMIN 可用的服务人员选项接口或放开该端点数据权限过滤
- [ ] **工单号撞唯一键（任务 4）**：`generateOrderNo` 为「WO+日期+4 位随机」，注释称
      「重复由唯一键兜底重试」但代码无重试，撞号直接 409——后端补循环重试
- [ ] **反馈（任务 8）**：`close()` 办结不推 WS（仅 sendMessage），对端最长滞后一个轮询周期
      （STOMP 在线 30s）——close 也 convertAndSend 即可，前端零改动；
      `GET /feedbacks` 缺 `keyword` 参数（前端搜索现为前端过滤，仅覆盖已加载页，
      代码留有切换服务端过滤的锚点注释）
- [ ] **评价（任务 11）**：unsatisfied 接口无 `hasFollowup` 参数（前端工作台现为 N+1
      请求核实方案，量级增长后应改服务端过滤）；`接口设计.md` 9.8 建议修订——
      跟进单实际为单 `content` 字段（文档三字段漂移）、followups 接口为纯数组非分页
- [ ] **预约（任务 9）**：`currentBookings` 仅计与时段模板起止精确相等的占用预约
      （模板子区间预约不计入，抽屉行数可能大于「已约 N」）——建议决策日志裁决是否改为
      时间重叠计数；`violation.max_count` 违约超限自动冻结联动行为（验收中实测触发过一次，
      已解冻恢复、阈值已还原）宜在文档中明确
- [ ] **系统用户（任务 12）**：创建用户表单「手机号选填」（前端无必填校验）vs 后端
      `@NotBlank`+唯一校验契约漂移（按决策日志 2026-09-08 口径记录待文档修订）；
      ADMIN 操作日志当前 0 行 = DataScopeInterceptor 按 community_id 过滤现状
      （种子数据无其可见日志行，非缺陷，社区内产生操作后自然出现）
- [ ] **居民/审批（任务 5）**：入住申请审批通过接口响应实为申请 VO（接口文档漂移，
      前端已按实测契约对齐）
- [ ] **预约创建请求体漂移（R2-R3）**：`接口设计.md` 9.7.1.1 创建预约请求体示例为
      `timeslotId`/`participants`，与后端 `CreateReservationDTO` 真实契约
      （resourceId/reserveDate/startTime/endTime 必填 + purpose/contactPhone/remark）
      **零交集**——按旧请求体实测必 400「资源不能为空」；前端类型已按真实契约适配
      留证（types/modules/reservation.ts），文档待主工作区修订
- [ ] **预约并发防重（R2-R2/R3）**：`resource_reservation` 无
      (user_id, resource_id, reserve_date, start_time) 唯一约束——应用层「同用户+同资源
      +同日期」占用态查重实测有效（命中返回 5002 拒绝）但仅顺序有效，
      selectCount→insert 非原子，**并发重复提交理论可穿透**；建议补唯一索引或提交幂等校验
- [ ] **可约时段接口口径（R2-R3/A6）**：`available-slots` 同日时段返回无稳定排序
      （无 ORDER BY，前端已按 startTime 兜底排序）；`currentBookings` 仅计与模板
      起止完全一致预约的口径**已记于上方「预约（任务 9）」条目**，A6 月历日格徽标
      已改用列表口径绕开，建议决策日志一并裁决；另（R2-A6 补录）：资源 `capacity`
      语义为「容纳人数」，而 `resource_reservation` 占用按「每时段次数」计，同日
      多时段时「日容量合计（Σ 该日时段 maxBookings）」与资源容量两套口径并存
      （种子健身房：日容量 20/日 vs 容纳 10 人），管理端月历徽标分母按日容量合计，
      是否后端统一口径待裁决
- [ ] **社区结构批量能力（R2-A7）**：①楼栋/单元/房屋批量创建端点缺失——前端以
      循环单建替代（逐个失败不中断，房屋单次上限 100 套）；②管理员直建居住关系
      端点缺失（POST /residence-relations 不存在，关系建立仅「居民申请→管理员审批」
      一条真实链路）——「登记住户」降级为跳转入住申请审批页引导；③`CreateHouseDTO.area`
      实为 @NotNull 必填，前端 `IHouseDTO.area` 标注可选（类型漂移，批量对话框已按
      必填处理）；④`GET /houses/{id}/residents` 手机号字段实为 `residentPhone`
      （`接口设计.md` 9.2.3.2 示例 `phone` 为漂移），VO 实际还返回 relationType/
      houseLocation（文档未列全），`IHouseResident` 已修正对齐
- [ ] **楼栋删除事务级联（R4-D2）**：`DELETE /buildings/{id}` 现状为空楼栋（无单元）
      直接软删成功、有单元即拒（5102，`BuildingService.java:62-72`），无事务级联——
      非空楼栋删除现由前端自底向上编排（逐单元删房屋→删单元→删楼栋，
      `StructureTreePane.vue` runBuildingCascade），**非原子，中途失败留下部分删除状态**
      （前端已做范围确认+进度+失败剩余明细汇报）；建议参照社区级联删除（stage-50-1b
      `DELETE /communities/{id}` 事务模式）提供事务性级联端点（或 `cascade=true` 参数），
      就绪后前端编排可整体替换
- 注：工单/统计侧的 ①~⑧ 号待补接口沿用上方既有清单，不重复。
- 注（R2 轮核查无缺口项，2026-09-12）：任务 R1 核查 `NotificationVO` 同时含
  `sourceType`/`sourceId`（messaging/vo/NotificationVO.java:32-36，from() 全量映射），
  通知跳转映射无能力缺口；任务 R3 核查居民查本人居住关系端点
  `GET /residents/{id}/residences` 存在且 RESIDENT 白名单内、业务层限本人；任务 R2
  定性「我的预约重复」为库内历轮验收残留行、后端忠实返回库内数据（非 join 扩散）。
  三项均无后端能力缺口，故不单列条目。

## 管理端遗留 Minor 清单（延后项，2026-09-12）

> 全部为各任务评审裁定「不阻塞、延后处理」的 Minor，逐条照抄任务账本
> （`.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/progress.md`）并补文件:行定位，
> 供用户整轮审查时逐条裁定（修/不修/转后端）。均不影响当前功能正确性。

**基建/侧栏（任务 1）**
1. 旧路径字符串 redirect 不携带 query（如 `?communityId=x`），社区上下文已由新页树导航自承，无功能损失 — `frontend/src/router/admin.ts`（redirect 段）
2. 任务 1 报告断言计数 42vs29 口径（cosmetic）— `.superpowers/sdd/ADMIN_BEAUTIFY_PLAN.md/task-1-report.md`

**看板（任务 2）**
3. 趋势卡 MOCK 占位待后端按日历史快照接口就绪后整块替换（全局检索 `MOCK` 可定位） — `frontend/src/views/admin/statistics/DashboardOverview.vue:53-72`
4. 环图/堆叠条百分比 Math.round 之和可能≠100（与设计稿同口径，未做最大余数修正） — `frontend/src/views/admin/statistics/DashboardOverview.vue:233,277`
5. StatisticsView 并入时 h1 移除超出简报字面（已披露，判定合理） — `frontend/src/views/admin/statistics/StatisticsView.vue`
6. 任务 2 报告断言计数口径（报告精度） — `task-2-report.md`

**社区结构（任务 3）**
7. 树加载 1+C+B 个串行请求放大（管理端规模可接受；社区数极大时宜改懒加载子级） — `frontend/src/views/admin/community/StructureTreePane.vue:84-105`
8. `selectedBuildingNode!` 非空断言易碎 — `frontend/src/views/admin/community/StructureTreePane.vue:972`
9. 对话框 clearValidate 时序依赖（打开后清校验的写法） — `frontend/src/views/admin/community/BuildingEditDialog.vue:60`（同款 `CommunityEditDialog.vue:45`、`UnitEditDialog.vue:94`）

**工单（任务 4）**
10. 详情页 2 处 `#fff` 硬编码与报告「全 token」声明不符（与 staff 端先例同款） — `frontend/src/views/admin/workorder/WorkOrderDetailView.vue:409,613`
11. 派单/改派共用对话框标题固定「派单 · 单号」（改派态按钮文案已动态、标题未随动） — `frontend/src/views/admin/workorder/WorkOrderDetailView.vue:331`
12. `handleReject` reason→remark 顺修未在任务报告声明（修复本身正确，仅披露缺失） — `frontend/src/views/admin/workorder/WorkOrderDetailView.vue:130`
13. 类别抽屉 `initialized` 闩锁首次加载失败后不重试（需重开抽屉） — `frontend/src/views/admin/workorder/ServiceCategoryDrawer.vue:37-38,50`

**居民（任务 5）**
14. 切 Tab 每次重复 `loadStats`（无节流；动作后刷新统计卡为有意行为） — `frontend/src/views/admin/resident/ResidentManageView.vue:33-38`
15. 关系 Tab 预选居民依赖远程搜索前 20 条，居民超 20 且目标不在其中时下拉回显裸 ID — `frontend/src/views/admin/resident/ResidenceRelationListView.vue:71`
16. `depositAmount`（表单字段名）/`deposit`（后端 DTO 名）双名并存（提交侧已映射并留注释） — `frontend/src/views/admin/resident/ResidenceApplicationListView.vue:117-118`
17. 统计卡第三卡标签口径：稿面「本月新增」后端无时间参数不造假，换真实指标「已冻结账号」 — `frontend/src/views/admin/resident/ResidentManageView.vue:38-40`

**公告（任务 7）**
18. 下线成功 toast 文案「公告已撤回」与按钮「下线」混称（后端端点即 withdraw，未在报告声明） — `frontend/src/views/admin/notice/NoticeManageView.vue:294`
19. 发布确认框文案组合方式变化（标题改为动态拼公告标题，零信息损失） — `frontend/src/views/admin/notice/NoticeManageView.vue:258-260`

**反馈（任务 8）**
20. keyword 前端过滤命中仍触发一次冗余 `load()`（`handleSearch` 重置页码并重拉当前页） — `frontend/src/views/admin/feedback/FeedbackManageView.vue:117`
21. 轮询到达触发整表替换+强制滚底（HEAD 既有行为，本轮未改） — `frontend/src/views/admin/feedback/FeedbackManageView.vue:259`
22. 附件删除遮罩 `rgba(0,0,0,0.55)` 硬编码非 token 新增 — `frontend/src/views/admin/feedback/FeedbackManageView.vue:986`

**预约（任务 9）**
23. violate/confirm 弹窗文案按后端真实行为适配（确认/完成必填理由），与后端行为一致，报告记一笔 — `frontend/src/views/admin/reservation/ReservationManageView.vue:489-492`
24. 「累计违约」统计卡口径不含看房违约（违约接口仅按居民分页查询） — `frontend/src/views/admin/reservation/ReservationManageView.vue:37`
25. 违约 Tab 双空态冗余（周视图空态与违约列表空态并存） — `frontend/src/views/admin/reservation/ReservationManageView.vue:706,737`
26. violations 深链（`?tab=violations`）仍会拉取周视图数据 — `frontend/src/views/admin/reservation/ReservationManageView.vue:433`

**房源（任务 10）**
27. 居民端 `ViewingAppointmentList` 幽灵字段泄漏（`visitorCount`/`appointmentNumber` 后端不返回 → 渲染「（ 人）/undefined」，宜由游客/居民端会话收口） — `frontend/src/views/resident/ViewingAppointmentListView.vue:135,139`
28. guest/resident 房源详情 `rentType` 双 undefined 坍缩为空白（标签 map 未命中且原值 undefined） — `frontend/src/views/guest/HousingDetailView.vue:175`、`frontend/src/views/resident/HousingDetailView.vue:298`
29. 「新增房源」按钮位置偏离稿（有据记录） — `frontend/src/views/admin/housing/HousingListView.vue:379`
30. Tab 切换重复触发统计请求（5 请求无节流） — `frontend/src/views/admin/housing/HousingManageView.vue:34-36`

**评价（任务 11）**
31. `loadWorkbench` 无 catch 静默失败 — `frontend/src/views/admin/evaluation/EvaluationManageView.vue:121`
32. `currentTab` 不随外部 query 变化同步（仅初始化读取一次） — `frontend/src/views/admin/evaluation/EvaluationManageView.vue:45`
33. 星色映射隐式依赖 el-rate 阈值数组顺序 — `frontend/src/views/admin/evaluation/EvaluationManageView.vue:239-240,318-323`

**系统（任务 12）**
34. 绑定社区 chip hover `#fff` 硬编码（同款先例） — `frontend/src/views/admin/auth/SysUserListView.vue:693-695`
35. 绑定 chips 加载失败静默置空（catch 返回空数组） — `frontend/src/views/admin/auth/SysUserListView.vue:169-171`
36. 任务 12 报告断言计数/账号尾号精度瑕疵（报告精度） — `task-12-report.md`
37. `log.retention_days` 控件 `:step="30" step-strictly` 无法输入 730（50-1b 既有观察项，校验逻辑属红线未动） — `frontend/src/views/admin/auth/GlobalConfigView.vue:138-139`

### R2 轮遗留（2026-09-12 验收修复轮补录）

> 编号接续第一轮 1~37；来源同上（账本 R2-* 条目 + round2-task-*-report.md 疑虑节，
> 2026-09-12 轮终审补录）。「流程/产物质量」组为过程产物瑕疵非代码缺陷，
> 其余口径与第一轮一致：不阻塞、延后处理，供用户整轮审查时逐条裁定。

**通知（R2-R1）**
38. 「全部已读」成功后 `load()` 沿用当前页号，「加载更多」翻页场景可重复追加（建议先 `page=1` 再 load） — `frontend/src/components/business/NotificationList.vue:143`（追加段 :93-98）

**流程/产物质量（R2-R2）**
39. R2 审查包为累计 diff 且超长截断（打包质量） — `round2-r2-review-package.txt`
40. R2 报告「同批次内不存在同秒成对出现」措辞歧义（排除双击的论证表述可更严谨） — `round2-task-R2-report.md` §1.2
41. R2 删前留证文件为 GBK 编码（跨工具读取需转码） — `Temp/round2_r2_predelete_evidence.txt`

**居民预约三段流（R2-R3）**
42. 资源详情页深链在「生效关系空集或关系接口失败」时放行渲染（列表页已拦截；详情页社区归属校验在空集时不生效） — `frontend/src/views/resident/ResourceDetailView.vue:73-76`
43. 预约子页从第三步「上一步」回步骤 2 不重拉时段余量（停留期间余量可能已变） — `frontend/src/views/resident/ReservationCreateView.vue:46`（gotoStep；回退按钮 :474-475）
44. 三个新视图共 15 处 `#fff` 硬编码（居民端先例从轻） — `frontend/src/views/resident/ResourceListView.vue`（3 处）、`ResourceDetailView.vue`（6 处）、`ReservationCreateView.vue`（6 处）
45. R3 审查包累计 diff 截断（打包质量） — `round2-r3-review-package.txt`

**看板（R2-A4）**
46. 工单行点击断言退化为 `path.startswith`（未校验详情 id） — `frontend/_shot_r2_dashboard.py:171,189`
47. 预约动态未按状态过滤（全部状态入列，「最近发生事项」语义；如需只看待审核再加过滤，取舍留用户） — `frontend/src/views/admin/statistics/DashboardOverview.vue:306-349`

**预约管理（R2-A6）**
48. 月历日格徽标口径实际含 CONFIRMED 防御计数，报告「与 OCCUPYING_STATUS 同口径」表述窄于代码 — `frontend/src/views/admin/reservation/ReservationManageView.vue:367`
49. 主布局 CSS 注释「340px」未同步实际 360px（修徽标折行改宽时注释漏改） — `frontend/src/views/admin/reservation/ReservationManageView.vue:1121`（实值 :1125）

**社区结构（R2-A7）**
50. 批量对话框在全部失败时仍关闭并 emit saved（丢输入上下文；成功/部分失败汇报不受影响） — `frontend/src/views/admin/community/BuildingBatchDialog.vue:122-123`（`UnitBatchDialog.vue:90`、`HouseBatchDialog.vue:223` 同款）
51. 住户抽屉加载失败 catch 静默置空态（无错误提示） — `frontend/src/views/admin/community/HouseListView.vue:524-525`
52. A7 报告截图计数笔误 6 vs 7（报告精度） — `round2-task-A7-report.md` §三.2

### R3 轮遗留（验收修复第三轮补录，明细见 round3-task-C12/C3/C4-report.md §4）

> 编号接续 38~52；来源 round3-task-*-report.md 疑虑节。口径同前：不阻塞、
> 延后处理，供用户整轮审查时逐条裁定（修/不修/转后端/人工复核）。

53. 真实 autofill 效果需用户在 Chrome 目检（Playwright 无法触发真实 `:-webkit-autofill`；自动化仅保证规则加载 + 正常态零回归；预期无蓝色中带、整框白底、文字主色深灰） — `frontend/src/styles/index.css`（全局 autofill 规则块）
54. 极端语境（未来出现非白底输入框，如深色页脚订阅框）时全局 autofill 白色内阴影需按语境覆盖（当前全站输入语境已核实均白底，不受影响） — `frontend/src/styles/index.css`（同上规则块）
55. 整栋创建单元前缀默认取楼栋**前缀**，批量建单元默认取完整**楼栋名**，两处默认值口径不同（各自忠实任务书原文，统一与否留用户裁决） — `frontend/src/views/admin/community/BuildingCreateDialog.vue`（`unitNameOf` 注释）、`UnitBatchDialog.vue`
56. 整栋创建为循环单建 N+1 请求放大（上限场景约 310 请求；后端批量端点就绪后执行层可整体替换，创建循环已收敛在 `handleSubmit` 单函数；对应后端适配清单「社区结构批量能力」条目） — `frontend/src/views/admin/community/BuildingCreateDialog.vue`
57. 单元前缀联动闩锁为一次性语义（手动改过单元前缀后，再改楼栋前缀不再联动，需手动同步；实测符合直觉但属取舍） — `frontend/src/views/admin/community/BuildingCreateDialog.vue`
58. 触屏端卡片快捷钮 hover 不可达（已按触屏路径兜底：块点击信息卡 footer 编辑/删除直达；原生长按菜单如需另行评估） — `frontend/src/views/admin/community/StructureTreePane.vue`（`.block-actions`）
59. 卡片快捷编辑不含状态字段（状态变更须走 updateHouseStatus 留痕接口；如需「卡片改状态」应作独立快捷入口带备注，未纳入本轮最小集） — `frontend/src/views/admin/community/HouseEditDialog.vue`
60. 楼栋视图空单元分组由过滤改为可见（本任务有意行为变化——空单元也要有「＋」入口，满数据楼栋零差异；如不喜欢空单元标题行可裁决回退） — `frontend/src/views/admin/community/StructureTreePane.vue`（`gridGroups`）

### R4 轮遗留（验收修复第四轮补录，明细见 round4-task-D1/D2/D3-report.md §5）

> 编号接续 53~60；来源 round4-task-*-report.md 疑虑节（§5）。口径同前：不阻塞、
> 延后处理，供用户整轮审查时逐条裁定（修/不修/转后端/人工复核）。
> 已落「后端适配清单」的条目（R4-D2 楼栋删除事务级联端点）不在此重复。

61. 无限过渡方案依赖 Chrome 继续以 background-color 途径强加 autofill 底色（历史上有过变化；若未来改用 box-shadow 等其他属性则 v2 失效、需届时再评估，当前 Chrome 含 13x 系列仍是 background-color 途径、方案有效） — `frontend/src/styles/index.css`（全局 autofill v2 规则块）
62. 非事务编排的并发漂移窗口：范围统计与确认执行之间他人增删房屋/单元时，编排按严格停止处理（多出房屋→单元删除 5103、已删房屋→404 亦停止），剩余明细与树刷新均反映真实状态但需用户重试（后端事务级联端点就绪后此窗口消失） — `frontend/src/views/admin/community/StructureTreePane.vue`（楼栋级联删除编排）
63. 级联范围统计逐单元拉房屋清单上限 200/单元（与树单元加载同口径；超大量需分页拉取，归入 Minor #7 同类，后端批量/级联端点就绪一并解决） — `frontend/src/views/admin/community/StructureTreePane.vue`（级联范围统计）
64. 空楼栋「不允许删」的原始感知未获复现：后端核实本就允许删（原因为前端文案误导+非空楼栋无路径的混合印象，本轮已修正）；若用户实际遇到过空楼栋删除被拒请提供复现数据（不排除当时树数据未刷新导致误判为非空） — `frontend/src/views/admin/community/StructureTreePane.vue`（空楼栋直删确认口径）
65. （过程产物）注入 500 的控制台豁免口径：字面 HTTP 500 必然产生一条浏览器资源加载错误（非产品代码输出），两脚本按精确 URL 豁免该一条，后续轮次复用请保留该豁免口径 — `frontend/_shot_r4_building.py`、`frontend/_shot_r4_grid.py`
66. 触屏无框选/多选：框选仅响应鼠标（`pointerType === 'mouse'`，触屏拖拽保留原生滚动不被劫持），多选不做降级模拟，触屏仍可走信息卡内编辑/删除兜底（第三轮 C4 路径）；如需触屏「长按进入多选模式」请反馈另立任务 — `frontend/src/composables/useGridSelection.ts`（容器指针事件 mouse 限定）
67. Esc 全局监听边界：监听在窗口层，关闭信息卡/对话框的 Esc 会顺带清空网格选区（多选场景下无副作用——对话框打开期间本不应操作选区，已按此口径实现） — `frontend/src/composables/useGridSelection.ts`（Esc 全局监听，onMounted/onUnmounted 挂卸）
68. 批量删除可选目标=当前网格已加载房屋（单页 200 上限，与树/网格加载同口径，归入 Minor #7 同类）；部分失败时逐单元局部刷新、失败房屋留存原位便于重试（重试需重新勾选，选区按规格清空） — `frontend/src/views/admin/community/StructureTreePane.vue`（`handleHouseBatchDelete`）
69. 批量确认文案一处复数适配：引用保护提示句「若该房屋存在…」在批量语境适配为「若房屋存在…」（唯一措辞差异，特此报备；若需连「该」字也保留请反馈即改） — `frontend/src/views/admin/community/StructureTreePane.vue`（批量删除确认框）

## 验收数据残留汇总（2026-09-12 记录，未执行任何数据库清理）

> 照抄各任务报告原文口径，供用户整轮审查时裁定清理或保留为演示数据。

| 来源 | 残留 | 清理方式（照抄任务报告） |
|------|------|--------------------------|
| 工单（任务 4 及历轮） | 4 张 CANCELLED「美化验证·…」测试工单（标题带 nonce 可辨；历次中断运行的同类残留亦已全部置 CANCELLED） | 工单无删除端点，取消为最干净终态；如需零残留须直接清库 |
| 居民（任务 5） | `e2e_res_1789161742`（验收居民 ACTIVE）+ `e2e_res_162022`（调试中途失败遗留，已冻结）；入住申请 5 APPROVED + 5 REJECTED（备注「管理端美化验收测试申请」）；居住关系 4 条 MOVED_OUT + 关联租约 4 条 MOVED_OUT（房屋状态已回翻） | 可走 SQL（residence_application / residence_relation / lease_record / resident 按 username 定位），或保留作为演示历史数据 |
| 租住（任务 6 及修复轮） | 「任务6验收-*」/「任务6修复验证-并发-*」标记租约（账本计 26 条；任务 6 报告口径：任务前基数 5 条、报告时点全表 38 条，均为 resident1 名下）+ 无备注 UI 新建产物 | 无删除接口，只能直接操作数据库（外键安全删除相应标记行），留待用户裁决 |
| 看房（任务 10） | ~18 条「验收看房×」终态记录（已取消为主，含已完成/已违约各 2）挂种子房源；既有 2 条 TO_CONFIRM（演示数据，未触碰）；他会话遗留房源 smoke-sale-housing（#5，house 201）未触碰 | 看房预约无删除接口 |
| 评价（任务 11） | 12 条评价（9 满意 + 3 不满意，其中 2 条各 1 条跟进）标题带「美化11」前缀 + 对应已完成工单；过程中 3 张 PENDING 工单（id 63/64/65）已全部取消、零悬挂 | 评价/跟进无删除接口，如需零残留需 DBA 直删（evaluation / followup / 对应工单链） |
| 预约（任务 9，补充记录） | 演示居民名下 4 条违约记录与若干 CANCELLED/REJECTED/COMPLETED 预约（真实状态机流转产生；期间触发一次违约超限自动冻结，已解冻恢复 ACTIVE，`violation.max_count` 已恢复 3）<br>**【2026-09-12 R2 轮已清理】**上列残留已由 R2-R2 授权清理（resource_reservation id 2~27 共 26 行 + violation_record id 1~6 共 6 行，删前留证 Temp/round2_r2_predelete_evidence.txt），仅保留 id=1 demo 行（2026-09-16 10:00-11:00 已取消）为唯一演示数据 | 已清理完毕；如需连 id=1 一并清零由用户裁决 |
| 预约（R2-R3，2026-09-12 追加） | R3 验证新增 2 条 PENDING 预约：id=28（2026-09-14 14:00-18:00）、id=29（2026-09-15 09:00-12:00），resident1 名下，不同日期时段不构成外观重复 | 本任务无删除授权未处理，待用户裁决（如需可管理员拒绝或 SQL 清理） |
| 违约（R2-A6，2026-09-12 追加） | 演示居民（resident1）名下 4 条违约记录，累计已超还原后冻结阈值 3（冻结仅在违约处置时点评估，居民当前仍 ACTIVE、不影响使用） | 违约记录无删除端点，待用户裁决清理 |
| 预约（R2-A6，2026-09-12 补录） | A6 验收产生 19 条终态预约行（COMPLETED 5 / REJECTED 5 / VIOLATED 5 / CANCELLED 4，零占用态残留；违约 4 条即出自该批，见上行），动作不可逆未删（无删除授权） | 预约无删除接口，待用户裁决（如需零残留须 SQL 清理或重建 V6 种子库） |
| 反馈（任务 8，补充记录） | 7 条「任务8验收反馈-*」（id 20~26）CLOSED 留档 + 1 个图片附件文件 | 反馈无删除接口；库内暂无 PENDING 演示数据，如需可居民端随手提交（报告并建议后续给 V6 补反馈种子） |
| 系统用户（任务 12） | 2 个冻结测试账号 `tmp_staff_t12`、`tmp_t12_89187238`（终态均已冻结留痕，superadmin 可见） | 后端无用户删除接口，须后端出删除能力或直接清库 |
| 结构（他人会话遗留，R2-A7 披露） | 1 号楼下空单元「二单元」（unit id=14，2026-09-12 15:27 创建、无房屋），非本轮任务产物，A7 按领地纪律未动 | 待协调确认归属后由管理端正常删除即可 |

## 管理端裁决记录（2026-09-12 用户定案）

- **D1 底色**：~~管理端用稿内暖白画布自成体系~~ **已修订（2026-09-12 二次裁决）**：
  用户复核后定案**全站画布统一冷灰**（暖白看着犯困）——`--admin-canvas` 与
  `--color-bg-warm` 均改为 `var(--color-bg)` 兼容别名（暖白 #f7f5f2 弃用），
  auth/403/404 四页底色同步切换；管理端仅侧栏深蓝与白卡自成体系。
  2026-09-10 设计方向中「米白暖灰底」的画布部分就此废止（侧栏/品牌蓝/摄影图不变）
- **D2 公告契约**：管理端公告表单的 priority/type/expireTime 字段
  **保持现状照常提交，不删不改**——后端主工作区测试已跑完，契约差距后续在后端侧适配
- **D3 提交纪律**：本轮（管理端美化全部任务）一律不做 git commit，改动全留工作树，
  用户整轮审查后再处理；验收截图按现有规则本地保留不上传，截图/回归脚本也不入库
- **配色纪律**：参考设计稿成体系搭配（画布/卡片/交互三层明度梯度 + 语义 soft 色对 +
  文字三档灰阶 + 品牌蓝克制使用），映射 variables.css token 增量，组件内禁止硬编码 hex

## 设计约定（2026-09-12 验收后裁决）

> 来源：验收修复第二轮用户裁决（`ROUND2_FIX_PLAN.md` 全局约定 4），确立为**全站
> 设计约定**；本轮新增页面已按此执行，既有页面本轮不动（用户后续逐页裁决）。

**少用大表单三原则**（按优先级依次选用）：

1. **能分步不分屏**——多输入环节拆步骤轨/分段卡，单屏只呈现当前步；
2. **能抽屉不弹窗**——从属信息与详情类维护用侧滑抽屉，不堆弹窗；
3. **能对话框不整页**——轻量录入用对话框完成，不新开整页大表单。

**拆分阈值**：单表单超过一屏、或超过 6 个输入组，必须拆分（分步 / 分区 / 抽屉）。

**本轮示范（R2 轮新增页面，后续页面照此执行）**：

- 居民端预约子页三步式（R3）：左侧步骤轨「选日期 → 选时段 → 确认提交」，
  已完成步可回跳、未解锁步置灰，右侧单步内容卡；
- 三个批量对话框（A7）：批量建楼 / 批量建单元 / 房屋批量生成，均为
  「分区布局 + 生成预览表 + 确认执行」的一屏紧凑对话框；
- 住户信息抽屉（A7）：房屋维度的住户查看与「办理搬出」走侧滑抽屉，不新开页面。

## 用户反馈的界面问题（2026-09-10）

- [ ] 游客端（免登录展示端）页面空乏，布局偏空
- [ ] 居民端首页公告无图文封面，样式模板化
- [ ] 各端列表布局过于模板化，缺少贴合业务的设计感
- [x] ~~服务人员端仪表盘/列表呆板~~ **服务人员端 4 页已全部完成**
      （2026-09-11：仪表盘重构 + 工单列表卡片化 + 工单详情三栏 + 消息中心复用共享组件）
- [ ] 管理端：导航冗余（社区管理 5 个子页跳转项）、全是表格列表布局、
      数据少的页面"像没渲染"、大量横向滚动条
