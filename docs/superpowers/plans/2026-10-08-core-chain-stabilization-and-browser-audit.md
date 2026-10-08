# 核心业务链稳定化与浏览器模拟审核实施计划

> **For agentic workers:** 本计划按任务逐项执行，每项必须先有可复现测试，再实施最小修复，最后回归验证。

**目标：** 在不破坏现有未提交改动的前提下，修复入住申请/租约唯一性、租约群聊时机与幂等、看房时段入口可达性三个阻塞域，并通过真实浏览器完成一次核心链路与交互设计审核。

**架构：** 以房屋行锁作为入住申请竞争的串行化边界，将“申请提交—审核—租约—群聊”约束集中在业务 Service 层；不依赖前端按钮防重复，也不把每个 DEF 当成独立补丁。看房时段先按当前设计追踪真实入口，只有确认缺失才修改 UI/路由。浏览器验证使用本地前后端真实服务和 Playwright，分别记录功能结果、控制台错误、页面截图与交互观察。

**技术栈：** Spring Boot 4.0.6、MyBatis-Plus 3.5.16、MySQL、Vue 3.5.42、TypeScript 5.9.3、Vite 8.2.2、Vitest 4、Playwright CLI/Python Playwright。

## 全局约束

- 不回滚、不覆盖当前工作区已有未提交改动；本轮只修改本计划列出的文件。
- 不通过修改需求或测试预期来消除缺陷；设计口径不明的项先记录，不擅自改冻结设计。
- Entity/DTO/VO 字段只使用 `@Schema(description="...")` 注释；Controller 使用 `@Operation`；Service 复杂流程使用块注释。
- 数据库事实来源仍为 Flyway 迁移；若不需要结构变化，不新增迁移。
- 50 阶段只判断实现是否符合 30 阶段设计；需求满足性和交互体验观察单独记录，交由 60 阶段裁决。
- 现有工作区改动不参与本轮提交；验证命令必须明确区分本轮改动与既有改动的影响。

---

### Task 1: 建立当前基线与缺陷复现证据

**Files:**
- Read: `docs/50_系统测试/08_缺陷跟踪与回归.md`
- Read: `docs/50_系统测试/_中控.md`
- Read: `backend/src/main/java/com/community/residence/resident/service/ResidenceApplicationService.java`
- Read: `backend/src/main/java/com/community/residence/conversation/service/ConversationService.java`
- Read: `frontend/src/views/admin/housing/HousingListView.vue`
- Read: `frontend/src/views/admin/housing/HousingDetailView.vue`
- Read: `frontend/src/views/admin/lease/HousingLeaseHubView.vue`

- [x] **Step 1: 记录工作区基线**

Run:

```powershell
git status --short --branch
git diff --stat
```

Expected: 既有改动列表可保存；本轮不执行 `git reset`、`git clean` 或大范围格式化。

- [x] **Step 2: 固定根因判断**

确认以下事实：

```text
ResidenceApplicationService.create 目前仅阻止同居民/同房屋的 PENDING 申请；
ResidenceApplicationService.applyApproval 每次审批都会插入新 LeaseRecord；
ConversationService 的租约群聊幂等键是 lease_id，因此无法阻止上游重复创建租约；
HousingTimeslotController 与管理端时段页面均已存在，DEF-153 需要按真实导航路径复现。
```

- [x] **Step 3: 不修改代码，先运行相关现有测试**

Run:

```powershell
cd backend
./mvnw.cmd -Dtest=ResidenceApplicationServiceTest,ConversationLeaseGroupEnsureTest test
cd ..\frontend
npm run typecheck
```

Expected: 记录基线结果；如失败，区分既有失败与本轮新增失败。

- [ ] **Step 4: Commit**

不提交工作区既有改动；仅在后续实现完成后按任务范围提交。

---

### Task 2: 用房屋行锁保护入住申请竞争，并阻止重复有效业务链

**Files:**
- Modify: `backend/src/main/java/com/community/residence/community/mapper/HouseMapper.java`
- Modify: `backend/src/main/java/com/community/residence/resident/service/ResidenceApplicationService.java`
- Modify: `backend/src/main/java/com/community/residence/lease/service/LeaseService.java`
- Modify: `backend/src/main/java/com/community/residence/common/constant/ErrorCode.java`（仅当现有错误码无法表达重复有效申请）
- Test: `backend/src/test/java/com/community/residence/resident/service/ResidenceApplicationServiceTest.java`
- Test: `backend/src/test/java/com/community/residence/lease/service/LeaseServiceTest.java`（若现有文件已存在则扩展，不新造重复测试类）

**Interfaces:**
- Consumes: `HouseMapper.selectByIdForUpdate(Long id)`；`ResidenceApplicationMapper`、`ResidenceRelationMapper`、`LeaseRecordMapper` 的状态查询。
- Produces: 在同一事务内完成房屋锁定、申请/租约冲突检查、插入或审批；重复业务请求返回明确的现有错误码，不创建新副作用。

- [x] **Step 1: 写失败测试——同居民/同房屋已有 TO_SIGN 或 ACTIVE 租约时拒绝新申请**

测试需要构造：

```java
when(houseMapper.selectByIdForUpdate(1L)).thenReturn(vacantHouse);
when(applicationMapper.selectCount(any())).thenReturn(0L);
when(leaseRecordMapper.selectCount(any())).thenReturn(1L);

assertThatThrownBy(() -> applicationService.create(createTenantApplication()))
        .isInstanceOf(BusinessException.class);
verify(applicationMapper, never()).insert(any(ResidenceApplication.class));
```

- [x] **Step 2: 写失败测试——同一房屋已有其他居民待审核申请时拒绝第二个申请**

断言第二次提交不产生第二条 `residence_application`，并返回“房屋当前不可申请入住”或专门的冲突错误。

- [x] **Step 3: 写失败测试——审批前再次检查有效租约/居住关系**

模拟审批管理员读取到 PENDING 申请，但在锁内发现同房屋已有 `TO_SIGN` 或 `ACTIVE` 租约，断言：

```java
verify(relationMapper, never()).insert(any(ResidenceRelation.class));
verify(leaseRecordMapper, never()).insert(any(LeaseRecord.class));
verify(conversationService, never()).createLeaseGroup(any(), any(), any(), any());
```

- [x] **Step 4: 运行失败测试**

Run:

```powershell
cd backend
./mvnw.cmd -Dtest=ResidenceApplicationServiceTest test
```

Expected: 新增测试先失败，证明当前实现确实未覆盖目标场景。

- [x] **Step 5: 增加房屋行锁查询**

在 `HouseMapper` 增加：

```java
@Select("SELECT * FROM house WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
House selectByIdForUpdate(@Param("id") Long id);
```

不要替换通用 `selectById`；仅在写入竞争的事务路径使用。

- [x] **Step 6: 修改入住申请创建路径**

`ResidenceApplicationService.create()` 必须按以下顺序执行：

1. 通过 `selectByIdForUpdate` 锁定目标房屋；
2. 校验房屋当前状态；
3. 查询同居民/同房屋的 `PENDING` 申请；
4. 查询同房屋的其他 `PENDING` 申请；
5. 查询同居民/同房屋状态为 `PENDING`、`TO_SIGN`、`ACTIVE` 的租约；
6. 查询同居民/同房屋仍在住的居住关系；
7. 通过全部检查后才插入申请；
8. 自动审批继续复用同一事务和同一锁定对象。

错误提示必须告诉用户“已有待处理入住申请/已有租约/房屋已被其他申请占用”，不能只返回泛化的内部错误。

- [x] **Step 7: 修改人工审批路径**

`ResidenceApplicationService.approve()` 必须在条件更新前或紧接着重新获得房屋行锁，并在锁内再次检查：

- 房屋是否已经有有效租约；
- 是否已经有未迁出的居住关系；
- 是否存在其他已批准申请或待签约租约；
- 当前申请是否仍然是可审批状态。

只有冲突检查通过后，才调用 `applyApproval()`。

- [x] **Step 8: 收口管理方直接创建租约路径**

`LeaseService.create()` 在插入 `PENDING` 租约前复用同一套“同房屋/同租客的有效租约与居住关系”判断，避免绕过入住申请端点重新制造重复业务链。若现有人工登记确实允许创建草稿，至少禁止与 `TO_SIGN`、`ACTIVE` 重叠。

- [x] **Step 9: 运行单测并回归现有状态机测试**

Run:

```powershell
cd backend
./mvnw.cmd -Dtest=ResidenceApplicationServiceTest,LeaseServiceTest,ResidenceApplicationLeaseLinkTest,ResidenceApplicationVORelationTest test
```

Expected: 新增冲突测试通过，原有审批、撤回、自动审批和强关联测试继续通过。

- [ ] **Step 10: Commit**

```powershell
git add backend/src/main/java/com/community/residence/community/mapper/HouseMapper.java backend/src/main/java/com/community/residence/resident/service/ResidenceApplicationService.java backend/src/main/java/com/community/residence/lease/service/LeaseService.java backend/src/main/java/com/community/residence/common/constant/ErrorCode.java backend/src/test/java/com/community/residence/resident/service/ResidenceApplicationServiceTest.java backend/src/test/java/com/community/residence/lease/service/LeaseServiceTest.java
git commit -m "fix(lease): guard duplicate residence applications and leases"
```

仅当测试通过且文件确实属于本任务时执行；不得把工作区其他改动一起提交。

---

### Task 3: 收口租约群聊创建时机与幂等

**Files:**
- Modify: `backend/src/main/java/com/community/residence/conversation/service/ConversationService.java`（只有必要的纵深防御调整）
- Modify: `backend/src/main/java/com/community/residence/resident/service/ResidenceApplicationService.java`（若 Task 2 已覆盖则不重复改）
- Test: `backend/src/test/java/com/community/residence/conversation/service/ConversationLeaseGroupEnsureTest.java`
- Test: `backend/src/test/java/com/community/residence/resident/service/ResidenceApplicationServiceTest.java`

**Interfaces:**
- Consumes: `ConversationService.createLeaseGroup()` 与 `uk_conversation_type_related`。
- Produces: 一个租约最多一个活跃或关闭的 `LEASE_GROUP`；重复审批、补建和并发调用不产生新租约、新会话或重复参与者。

- [x] **Step 1: 写失败测试——同一申请只产生一次租约群聊**

验证 `applyApproval` 只调用一次 `createLeaseGroup`，第二次重复审批不进入 `applyApproval`。

- [x] **Step 2: 写失败测试——已有租约群聊时 ensure 只回读和补链**

验证 `ensureLeaseGroup()` 不插入新的 `Conversation`，只在 `lease_record.conversation_id` 为空时补写回链。

- [x] **Step 3: 写失败测试——关闭群聊不自动重开**

已有同租约 `CLOSED` 会话时，ensure 返回关闭会话，不创建第二个会话。

- [x] **Step 4: 实施最小修复**

保留现有数据库唯一键和并发异常兜底；只补充以下防线：

- `createLeaseGroup` 对 `leaseId == null` 直接拒绝或返回明确业务错误；
- 参与者补齐必须保持身份空间 `(owner_type, user_id)`；
- 申请审批路径不允许在租约插入失败后留下会话（依靠同一事务回滚）；
- 不为每个重复会话问题新增前端特判。

- [x] **Step 5: 运行会话与审批回归**

Run:

```powershell
cd backend
./mvnw.cmd -Dtest=ConversationLeaseGroupEnsureTest,ConversationServiceTest,ResidenceApplicationServiceTest test
```

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/community/residence/conversation/service/ConversationService.java backend/src/test/java/com/community/residence/conversation/service/ConversationLeaseGroupEnsureTest.java backend/src/test/java/com/community/residence/resident/service/ResidenceApplicationServiceTest.java
git commit -m "fix(conversation): enforce lease group lifecycle idempotency"
```

---

### Task 4: 复现并修复看房时段入口可达性

**Files:**
- Read/Modify only if reproduction proves necessary: `frontend/src/views/admin/lease/HousingLeaseHubView.vue`
- Read/Modify only if reproduction proves necessary: `frontend/src/views/admin/housing/HousingManageView.vue`
- Read/Modify only if reproduction proves necessary: `frontend/src/views/admin/housing/HousingListView.vue`
- Read/Modify only if reproduction proves necessary: `frontend/src/views/admin/housing/HousingDetailView.vue`
- Test: existing housing view test files under `frontend/src/views/admin/lease/__tests__/`

**Interfaces:**
- Consumes: `/admin/housing-lease` → 房源管理 Tab → 房源卡片。
- Produces: 管理方能从当前业务入口在一次可理解的路径内找到“看房时段”配置，并能保存后回读。

- [x] **Step 1: 运行前端既有测试与构建**

Run:

```powershell
cd frontend
npm run test -- src/views/admin/lease/__tests__/HousingLeaseHubView.spec.ts
npm run typecheck
npm run build
```

- [x] **Step 2: 先用真实浏览器复现导航**

从 `/admin/housing-lease` 登录管理端，记录：

- 房源管理 Tab 是否可见；
- 房源列表是否有“时段”入口；
- 点击后是否能到 `/admin/housings/:id#timeslots`；
- 时段区是否可见、按钮是否可操作；
- 保存后居民端是否能读取对应时段。

- [x] **Step 3: 只修复真实发现的问题**

如果入口存在但不可发现：优化入口文案、位置和导航提示；如果路由或 Tab 丢失：补齐容器接线；如果运行环境加载旧构建：不改业务代码，只记录环境原因。

不新增重复的时段配置页面，不修改冻结需求口径。

- [ ] **Step 4: 增加页面级回归断言**

至少覆盖：

```text
房源列表存在“时段”操作；
点击后进入房源详情并定位 #timeslots；
时段配置区显示当前记录；
新增/删除时段后重新加载仍保持结果。
```

- [ ] **Step 5: Commit**

仅在确有代码修复时提交：

```powershell
git add frontend/src/views/admin/lease/HousingLeaseHubView.vue frontend/src/views/admin/housing/HousingManageView.vue frontend/src/views/admin/housing/HousingListView.vue frontend/src/views/admin/housing/HousingDetailView.vue frontend/src/views/admin/lease/__tests__
git commit -m "fix(housing): make viewing timeslot entry discoverable"
```

---

### Task 5: 前后端构建和定向回归

**Files:**
- No new source file unless a missing focused test is required.
- Update only relevant existing test evidence documents after results are known:
  - `docs/50_系统测试/08_缺陷跟踪与回归.md`
  - `docs/50_系统测试/_中控.md`

- [x] **Step 1: 后端定向测试**

```powershell
cd backend
./mvnw.cmd -Dtest=ResidenceApplicationServiceTest,LeaseServiceTest,ConversationLeaseGroupEnsureTest,ConversationServiceTest,HousingAvailableSlotsTest,BatchGenerateHousingTimeslotTest test
```

- [x] **Step 2: 前端定向测试、类型检查、构建**

```powershell
cd frontend
npm run test -- src/views/admin/lease/__tests__/HousingLeaseHubView.spec.ts
npm run typecheck
npm run build
```

- [x] **Step 3: 更新测试记录**

只记录真实结果：修复项、未修复项、设计待裁决项、浏览器验证入口和截图路径。不得把定向通过写成 50 阶段整体通过。

- [ ] **Step 4: Commit**

```powershell
git add docs/50_系统测试/08_缺陷跟踪与回归.md docs/50_系统测试/_中控.md
git commit -m "docs(test): record core chain stabilization regression"
```

---

### Task 6: 浏览器模拟手测与 UI/交互审核

**Files:**
- Create: `output/playwright/core-chain-audit.py`
- Create: `output/playwright/` screenshots/traces only
- Read: relevant frontend views and runtime console/network output

- [x] **Step 1: 检查浏览器自动化依赖**

Run:

```powershell
Get-Command npx
python C:\Users\17841\.agents\skills\webapp-testing\scripts\with_server.py --help
```

- [x] **Step 2: 启动本地前后端**

使用项目既有启动方式，在持久终端中启动：

```powershell
cd backend
./mvnw.cmd spring-boot:run
```

另一个持久终端：

```powershell
cd frontend
npm run dev
```

不得在没有确认数据库与环境变量的情况下清理用户数据。

- [x] **Step 3: 编写浏览器 QA 清单**

至少覆盖：

- 管理员登录；
- 从租住与房源 Hub 进入房源管理；
- 找到并配置看房时段；
- 居民/游客读取可预约时段；
- 居民提交一次入住申请；
- 再次提交相同房屋申请，观察错误提示是否清楚；
- 管理端审核通过；
- 刷新后确认只存在一个租约和一个群聊入口；
- 重复点击审批或刷新重入时不产生重复数据；
- 观察页面的加载、空状态、错误反馈、按钮可发现性、移动/窄窗口布局。

至少加入两个探索场景：

1. 在审批按钮连续点击或网络延迟时重复提交；
2. 从房源列表、房源详情、租住 Hub 三个入口分别寻找时段配置。

- [x] **Step 4: 运行 Playwright 模拟手测**

等待 `networkidle` 后再定位 DOM；每次导航、Tab 切换、弹窗打开后重新检查页面状态。捕获：

- 页面截图；
- 浏览器 console error；
- 关键请求的 HTTP/业务码；
- 关键页面的 viewport/overflow 结果；
- UI/交互观察项。

- [x] **Step 5: 形成审核结论**

按以下四类输出，不再把所有观察都登记成 DEF：

```text
A. 阻塞缺陷：无法继续核心链路；
B. 设计/实现缺陷：与 30 阶段设计不一致；
C. 需求满足性差距：交给 60 阶段；
D. 视觉/交互优化项：不阻塞流程，单独排期。
```

- [x] **Step 6: 清理浏览器会话**

关闭 Playwright 浏览器和测试服务器前，保存必要截图与日志；不删除项目已有测试数据和上传资产。

---

### Task 7: 后续核心链审查安排

在 Task 6 完成后，按下面顺序逐链审核：

1. 房源展示 → 看房时段 → 看房预约 → 带看分配；
2. 入住申请 → 审核 → 待签约租约 → 租约群聊；
3. 承租确认 → 协议发起 → 双方确认 → 租约生效；
4. 退租申请 → 审核 → 房屋回收 → 押金原路退还；
5. 工单受理 → 派单 → 群聊 → 处理记录 → 评价。

每条链先做代码审查，再做接口/数据库状态审查，再做浏览器交互审查，最后只安排该链自己的手测，不再一次性打开全系统测试面。

### Task 7 当前进度：第一条核心链自动化续审

- [x] **房源展示 → 看房时段 → 居民预约 → 取消 → 会话回收**：浏览器完成一次真实交互；取消后会话由 `ACTIVE` 转 `CLOSED`，历史可读、输入框隐藏。
- [x] **管理端确认 → 分配带看人 → 带看人入群 → 完成**：隔离样本 `appointmentId=3` 完成真实浏览器回归；发现并修复 DEF-157（服务人员旧 R59 单聊与 R63 群聊分裂），修复后居民/服务人员双向消息互见。违约分支留待下一轮。
- [x] **游客取消预约身份校验专项**：已完成代码/接口审查并登记 DEF-158；发现设计要求的「手机号 + 预约编号」凭据链尚未实现，详情按 id 公开、取消需认证且无预约编号字段。本轮不改码，待用户裁决匿名凭据闭环或强制登录方案。

### Task 7 当前进度：第二条核心链自动化续审

- [x] **入住申请 → 管理端审核 → `TO_SIGN` 租约 → `LEASE_GROUP` 群聊**：隔离样本房屋 24 / 房源 3 / 申请 8 完成真实浏览器回归；申请回读 `leaseId=5`、`conversationId=10`，重复审批返回 5202 且不产生第二租约 / 群聊。
- [x] **会话参与者与消息链**：居民与社区管理员均可读取 conversation 10；居民发送消息后管理端读回同一会话消息。
- [ ] **后续**：进入第三条核心链「承租确认 → 协议发起 → 双方确认 → 租约生效」；DEF-148 价格自动带出仍作为需求满足性 / 交互优化项保留，不在本批擅自改动。

### Task 7 当前进度：第三条核心链自动化续审

- [x] **承租确认 → 协议发起 → 管理方确认 → 租约生效**：居民确认承租、管理端以社区默认模板发起协议并确认完成；协议 `SIGNED`、租约 `ACTIVE`、房屋 `OCCUPIED`、变更历史与群聊系统消息均已回读。
- [x] **居民端回读**：居民端显示「协议已签署 / 在租 / 待支付首期费用」，不重复要求居民二次确认。
- [ ] **后续**：进入第四条核心链「退租申请 → 审核 → 房屋回收 → 押金原路退还」。

### Task 7 当前进度：第四条核心链自动化续审

- [x] **退租申请 → 管理端审核 → 房屋回收 → 租约群聊关闭**：隔离租约 `leaseId=5` 完成真实浏览器回归；租约 `ACTIVE → MOVED_OUT`，房屋置空置，房源回收，居住关系终止，租约群聊关闭且历史只读。
- [x] **押金退还门禁**：样本押金状态为 `UNPAID`，管理端按钮禁用；接口返回 `5703`，未创建退款记录、未触发支付渠道。
- [ ] **后续**：用可控的已缴押金夹具或支付沙箱回执补验原渠道退款成功 / 失败重试；随后进入第五条核心链「工单受理 → 派单 → 群聊 → 处理记录 → 评价」。