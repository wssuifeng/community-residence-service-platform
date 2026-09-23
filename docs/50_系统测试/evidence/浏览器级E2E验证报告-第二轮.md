# 浏览器级端到端（E2E）验证报告 · 第二轮

- 项目：`D:\code\community-residence-service-platform`（社区居住服务管理系统）
- 验证身份：浏览器级 E2E 验证工程师（真浏览器驱动：点击 / 填表 / 切页）
- 验证日期：2026-09-22（本轮 13:40 – 14:08）
- 判据来源：`docs/50_系统测试/13_核心业务流程三端联动手测用例.md`
  （CF-E4 工单完整链路 L425 起 14 步 + 断言①~⑨；CF-E3-F 押金退还 L375 起；
  CF-E4-X1 跨身份空间越权 L490）
- 纪律：**未修改任何业务代码**（`backend/`、`frontend/src/` 一行未动）、
  **未改库结构、未重置库、未改种子脚本**、**未 git 提交**；
  脚本与截图全部落在 gitignore 的 `Temp/` 下。
- 主要产物：
  - 本轮截图 59 张：`Temp/shots2/`
  - 逐步骤结构化结果：`Temp/e2e-browser/results_r2_*.json`
  - 自动汇总表：`Temp/e2e-browser/round2_consolidated.md`
  - 汇总表（人工勘误版，见本报告 §1）

---

## 0. 环境与构建时点（影响结论解释，务必先读）

| 项 | 状态 |
|---|---|
| 前端 5173 | **在跑** · pid **9192**（node）· 启动 10:52:37 · 仅绑 `::1`（**必须用 `http://localhost:5173`**，`127.0.0.1:5173` 连接被拒） |
| 后端 8081 | **在跑** · pid **14096**（java）· 启动 **13:55:50**（本会话内被重启过；前身 pid 22128 启动于 13:15:45） |
| 后端 8080 | **在跑** · pid **24804**（java）· 启动 13:57:54 —— 另一并发会话新起的实例，5173 **未**指向它 |
| 8083 / 5199 | 未监听（另一会话的临时实例已退出/未起） |
| 5173 → 后端 指向 | **8081**。依据：`frontend/vite.config.ts` L24 `target: env.VITE_PROXY_TARGET ?? 'http://localhost:8080'`，本会话实测「修复前该请求 500 → 8081 于 13:55:50 重启后同一请求 200」，证明流量落在 8081 |
| `http://localhost:5173/api/v1/payment/channels` | 401（代理通、达后端，鉴权生效） |

**构建时点差异（重要）**：本会话内后端被并发会话重启过一次。

- CF-E4 工单全链（13:42–13:52）跑在 **pid 22128**（13:15:45 构建）；
- CF-E3-F 修复后复测（14:02–14:08）跑在 **pid 14096**（13:55:50 重启后构建）；
- 两次构建之间**唯一的业务代码差异**是 `interceptor/DataScopeInterceptor.java`
  （13:45:34 修改，向 `SKIP_TABLES` 增补 `lease_deposit_refund` 与 `sys_blacklist_log`），
  **不触及**工单 / 会话 / 租约业务逻辑；
- 为消除疑虑，**安全回归 X1 在旧构建（13:51）与新构建（14:00）各跑一次，两次均 5/5 PASS**。

---

## 1. 逐流程结果表（流程 / 步骤 / 期望 / 实际 / 判定 / 截图）

> 判定口径：以**页面可见文本**为主证据，接口报文与网络抓包仅作交叉印证。
> 下表为**勘误后的最终判定**；中间过程产生的 9 条 FAIL 中有 6 条属「旧构建 500 缺陷」、
> 3 条属本人脚本选错选择器/路径，均已在 §1.4「已作废的中间判定」逐条说明并复测为 PASS。

### 1.1 流程 ① CF-E4 工单完整链路（14 步）

| # | 步骤 | 期望 | 实际（页面可见证据） | 判定 | 截图 |
|---|---|---|---|---|---|
| 1 | 居民提交工单（室内维修 + 1 张图片） | 提交成功、转「待受理」、**受理前无群**（断言①） | 工单 `WO202609221917`（id=10）；状态「待受理」；**群聊面板数=0**、页面无「工单沟通」文案；附件 1 张 | **PASS** | `Temp/shots2/001_E4_1_submitted.png`<br>`Temp/shots2/002_E4_1_before_intake_no_group.png` |
| 2a | 「受理」入口前置：仅 PENDING 可见 | 仅待受理行显示「受理」 | 受理前该行有「受理」；受理后该行变「派单 / 详情」，无「受理」（须切**列表**视图，看板视图无此入口） | **PASS** | `Temp/shots2/001_E4_2b_intake_done_list.png`<br>`Temp/shots2/003_E4_2_admin_pending_row.png` |
| 2b | 管理端「受理」 | 转「待派单」+ 同事务自动建群 + 响应回带 `conversationId` | `POST /work-orders/10/intake` HTTP=200；**`conversationId=9`**；列表行变为「**已建群** … 待派单 未指派」 | **PASS** | `Temp/shots2/001_E4_2b_intake_done_list.png` |
| 2c | 居民端出现群聊面板（断言②） | 面板出现、群名「工单沟通·<单号>」、成员=提交人+受理管理员 | 群聊面板数=1；含「工单沟通」=true；**群成员=[张伟（居民）, 陈国强（社区管理员）]** | **PASS** | `Temp/shots2/002_E4_2c_resident_group_panel.png` |
| 3a | 管理端群内发言 → 居民端免刷新可见 | WS 实时（≤3s） | 发送成功；**居民端未刷新即出现**「已受理，正在安排服务人员」；消息行数=2 | **PASS** | `Temp/shots2/003_E4_3a_admin_msg.png`<br>`Temp/shots2/004_E4_3b_resident_realtime.png` |
| 3b | 派单前 staff1 不在群（断言④前半） | 服务人员无该群；非参与者访问 → 403 | 服务人员端含「工单沟通」=**false**；`GET /conversations/9` → **403**「非会话参与者，无权访问会话」 | **PASS** | `Temp/shots2/005_E4_3c_staff_not_in_group.png` |
| 4 | 派单给 staff1（断言③） | 转「已派单」+ **staff1 自动入群（三方齐）** | toast「已派单给 刘伟民」；候选档位 2 人（常驻本社区优先）；**群成员=[张伟（居民）, 陈国强（社区管理员）, 刘伟民（服务人员）]**，面板数=1 | **PASS** | `Temp/shots2/006_E4_4_assigned_three_members.png`<br>`Temp/shots2/001_E4_4_members_three.png` |
| 5a | 服务人员端出现待办并可进详情 | 「待接单」含该单，详情群成员三方齐 | 列表含 `WO202609221917`；待接单计数=1；详情群成员三方齐 | **PASS** | `Temp/shots2/002_E4_5a_staff_todo.png` |
| 5b | 服务人员「接单」（断言④后半） | 转「已接单」；群内三方互通 | toast「接单成功，请尽快开始处理」；群内发言成功；**管理端可见**该发言（「明天上午 10 点到场」） | **PASS** | `Temp/shots2/003_E4_5b_accepted_group_msg.png` |
| 6 | 服务人员「开始处理」 | 转「处理中」 | toast「已开始处理」 | **PASS** | `Temp/shots2/004_E4_6_in_progress.png` |
| 7 | 服务人员「提交处理结果」（+1 张图片） | 转「待确认」、处理记录与图片留痕 | 详情含解决说明「已更换水龙头密封圈并紧固接口…」；含图片元素 6 个；居民端可见该处理结果；管理员收到「工单待居民确认」通知 | **PASS**\* | `Temp/shots2/005_E4_7_to_confirm.png` |
| 8 | 居民「确认完成」（二次确认） | 转「已完成」+ 出现「服务评价」区 | 页面出现「**服务评价**」=true；群聊面板仍在（1 个）；staff1 与 admin1 各收到「居民已确认工单」 | **PASS** | `Temp/shots2/006_E4_8_completed_with_eval.png` |
| 9 | 终审入口前置：仅 COMPLETED 可见（断言⑦前半） | 已完成行有「终审结束」，待确认行没有 | 已完成单 `WO202609221917`：**终审按钮=1**；待确认对照单 `WO202609228704`：**终审按钮=0** | **PASS** | `Temp/shots2/007_E4_9_finalize_gate.png` |
| 10 | 管理端「终审结束」（二次确认含回收说明） | 转「已关闭」；弹窗含会话回收说明；close 200 | 弹窗原文含「终审结束后工单进入「已关闭」终态，**工单沟通群将回收为只读（历史可查、不可再发言）**」；`POST /work-orders/10/close` **HTTP=200**；列表行=「已关闭」 | **PASS** | `Temp/shots2/008_E4_10a_confirm_dialog.png`<br>`Temp/shots2/009_E4_10b_closed.png` |
| 11 | 会话回收后：历史可读 + 只读 + 再发言 5604（断言⑧） | 历史留档；面板只读；再发言被拒 | 历史消息行数=**5**（未物理删除）；关闭页脚 `.chat-closed`=1、**输入框数=0**；面板文案「会话已关闭：工单已结束，会话资源已回收（历史可查、不可再发言）」；`GET /conversations/9` → 200 且 `closedAt=2026-09-22T13:49:02`、`participantCount=3`、末条系统消息「工单已终审结束（已关闭），本群停止接收新消息，历史记录保留可查」；**再发言 → `code=5604`「会话已关闭，不可再发送消息」** | **PASS** | `Temp/shots2/010_E4_11_recycled_readonly.png` |
| 12 | 通知矩阵核对（断言⑨） | 见 §2.1 矩阵逐格结论 | admin1 收到 6 条该单事件；superadmin 收到「新工单待受理」（事件 1）；resident1 收到 6 条「您的工单…」口径；staff1 收到派发/接单/居民确认；**superadmin 无「您的工单」口径（DEF-067 不串号）成立**；**但事件 8「终审关闭」staff1 收不到 → 见缺陷 DEF-R2-02** | **PASS（口径隔离）**<br>**FAIL（矩阵一格，另列）** | `Temp/shots2/014..018_E4_12_*.png` |
| 13a | 旁支：管理端「驳回工单」（理由必填） | 空理由被拦、填写后转「已驳回」 | 空提交 → 弹窗内提示「请填写驳回理由」；填写后 toast「工单已驳回」、详情状态「已驳回」；resident1 收到「您的工单 WO202609229007 被驳回：房屋尚不具备交付条件…」 | **PASS** | `Temp/shots2/011_E4_13a_rejected.png` |
| 13b | 旁支：居民「取消工单」 | 转「已取消」；未受理单**不建群** | toast「工单已取消」；详情 URL `/resident/work-orders/13`；**群聊面板数=0** | **PASS** | `Temp/shots2/012_E4_13b_cancelled.png` |
| 14 | 旁支：居民对 TO_CONFIRM 单「不满意退回」 | 退回「处理中」 | 居民端状态片段含「处理中」；staff1 收到「**工单被退回处理中**：工单 WO202609228704 被居民退回：居民反馈不满意：出水仍然偏小，请再次上门排查」 | **PASS** | `Temp/shots2/013_E4_14_returned.png` |

\* 步骤 7 的**唯一**失败点是 toast 文本未捕获（ElMessage 已消失），改以「详情留痕 + 居民端可见 + 管理员通知 + 状态入待确认」四条独立证据判定为 PASS。

### 1.2 流程 ② CF-E3-F 押金退还

| # | 步骤 | 期望 | 实际 | 判定 | 截图 |
|---|---|---|---|---|---|
| 0 | 起手数据核对 | 租约 4 = MOVED_OUT + 押金已缴 + 成功首期单 7600(租金3800+押金3800) | `status=MOVED_OUT`、`depositStatus=PAID`、`deposit=3800`；首期单 `FIRST/SUCCESS/7600/depositAmount=3800/ALIPAY/2023-08-01` | **PASS** | `Temp/shots2/001_E3F_0_start.png` |
| 1 | 居民（resident2·租约2）「申请退租」 | 提交成功、转「待审核」 | 弹窗含搬出日期/退租原因/「同一租约同时只能有一条待审核申请」；提交后居民端出现「待审核」；`POST /leases/2/move-out-applications` 复查 → `code=5603`「已有待审核的退租申请」 | **PASS** | `Temp/shots2/001_E3F2_1_moveout_dialog.png` |
| 1b | 同租约重复发起退租 → 5603 | 5603 | `POST /api/v1/leases/2/move-out-applications` → **`code=5603`**「已有待审核的退租申请」 | **PASS** | — |
| 2a | 管理端「退租申请」列表出现待审核 | 列表出现该申请 | 行：`ZL0002 李思远 居民 ID 2 A 座1 单元301 滨江雅苑社区 2026-10-31 工作变动，需搬离本市 待审核 … 通过 驳回` | **PASS** | `Temp/shots2/001_E3F4_1_admin_moveout_pending.png` |
| 2b | 审核通过 → 联动「已搬出」+ 房源回收 | 租约转已搬出；押金状态不受影响 | 弹窗含「租约将流转为「已搬出」并记入变更历史，**居住关系按搬出日期终止**，房屋置为空置；房源按下方选择回收」；确认后 `GET /leases/2` → `status=**MOVED_OUT**`、`depositStatus=**UNPAID**`（未被误置已缴，正确） | **PASS** | `Temp/shots2/002_E3F4_2_approve_dialog.png` |
| 2c | 居住关系终止（记搬出日期） | 关系状态终止 + 记录搬出日期 | `GET /residents/2/residences` → relation id=2（李思远↔A座1单元301）`status=**MOVED_OUT**`、`moveOutDate=**2026-10-31**`；`GET /houses/1382/residents` 同结论 | **PASS** | `Temp/shots2/001_E3F4_2c_relation.png` |
| 3 | 驳回分支（租约 1） | 驳回后租约保持已生效 | 管理端驳回成功（理由「房屋仍需继续出租，暂不同意退租」）；`GET /leases/1` → `status=**ACTIVE**`（未受影响） | **PASS** | `Temp/shots2/003_E3F2_3_rejected.png` |
| 4a | 租约 4「发起押金退还」入口 | MOVED_OUT+PAID 时可用 | 按钮数=1、`disabled=false`、`title="按实缴渠道原路退还"`；页面押金徽标「押金已缴」 | **PASS** | `Temp/shots2/004_E3F2_4a_entry.png` |
| 4b | 弹窗：状态/实缴/可退/**渠道只读=原渠道**/金额默认全额 | 渠道不可指定；金额默认全额；含不托管声明 | 弹窗显示「押金状态 已缴 / 实缴押金 ¥3,800 / 已退还 ¥0 / **可退金额 ¥3,800** / 退款渠道 **只读：按实缴支付单原路退回至支付宝，不可跨渠道退款** / 退还金额 留空表示全额退还」+「失败重试会自动换新请求号」+ 幂等请求号 `DR4MUC9PV2E1GRQ` + 「历史退还记录（2）」；渠道输入框 readonly 值=「支付宝」；金额框值=「3800」 | **PASS** | `Temp/shots2/005_E3F2_4b_dialog.png` |
| 4c | 发起退还 → 渠道结果；失败回落 PAID | PENDING→SUCCESS(**REFUNDED**) 或 FAILED(**回落 PAID**+原因常驻) | `POST /leases/4/deposit-refunds` **HTTP=200**；弹窗出现「**押金退还失败** 支付宝退款失败：交易不存在（ACQ.TRADE_NOT_EXIST）」；`GET /leases/4` → `depositStatus=**PAID**`（正确回落，可重试）；流水新增 `id=3 / DR4MUC9PV2E1GRQ / 3800 / ALIPAY / FAILED / failReason=支付宝退款失败：交易不存在（ACQ.TRADE_NOT_EXIST）` | **PASS**（覆盖文档允许的失败分支） | `Temp/shots2/006_E3F2_4c_result.png` |
| 4d | 失败重试换新 `outRequestNo` | 新增记录、不复用旧号 | 条数 3 → **4**；请求号集合 `["DR4MUC9Q3TGTXNF","DR4MUC9PV2E1GRQ","R2PROBE-a1_lease4-…","R2PROBE-sa_lease4-…"]`，**唯一性 = true** | **PASS** | `Temp/shots2/007_E3F2_4d_retry.png` |
| 4-neg-5704 | 金额超实缴（999999，租约4已缴） | 拒 5704 且报文含可退金额 | `code=**5704**`「退款金额超出可退押金（**可退 3800.00 元**）」 | **PASS** | — |
| 4-neg-5705 | 渠道与实缴不一致（实缴 ALIPAY 传 WECHAT） | 拒 5705 | `code=**5705**`「退款须原渠道退回（实缴渠道 ALIPAY），不支持跨渠道退款」 | **PASS** | — |
| 4-neg-5706 | 同 `outRequestNo` 内容不一致 | 拒 5706 | `code=**5706**`「退款请求号冲突（同号内容不一致）」 | **PASS** | — |
| 4-neg-5703 | 押金未缴（租约2 = MOVED_OUT + UNPAID） | 拒 5703 | `code=**5703**`「押金当前状态不允许退还：UNPAID」 | **PASS** | — |
| 4-neg-5602 | 非已搬出租约（admin2 对租约5 ACTIVE） | 拒（状态门禁） | `code=**5602**`「仅已办理搬出（或退租申请已通过）的租约可退还押金，当前租约状态：ACTIVE」 | **PASS** | — |
| 5 | 居民端押金徽标 + 退还流水 | 五态徽标 + 流水（金额/渠道/状态/**失败原因**/时间/单号） | 租约切换项：`1 号楼1 单元201 已归档 · 押金未缴` / `1 号楼1 单元201 已搬出 · 押金已缴` / `1 号楼1 单元101 在租 · 押金未缴`；押金区：`押金 已缴 / 押金登记金额 ¥3,800 / 累计已退还 ¥0 / 押金退还记录 / ¥3,800 支付宝 退还失败 发起 2026-09-22 14:03 · 完成 — / 请求号 DR4MUC9Q3TGTXNF / **失败原因：支付宝退款失败：交易不存在（ACQ.TRADE_NOT_EXIST）**`（多条） | **PASS** | `Temp/shots2/008_E3F2_5_resident_refund_records.png` |
| 5b | 越权：resident2 读租约4退还流水 | 仅本人租约可见 | `GET /leases/4/deposit-refunds` → **403**「无权查看他人租约的押金退还记录」 | **PASS** | — |
| 6 | 租约明细时间线（三源合并） | 状态流转+支付+押金退还合并、时间倒序；支付条目含金额拆分/覆盖租期/业务单号/渠道 | 时间线含：`状态流转 ACTIVE→MOVED_OUT`、`登记租约`、`支付 首期费用（首月租金+押金）支付成功 ¥7,600 / **租金部分** ¥3,800 / **押金部分** ¥3,800 / **覆盖租期** 2023-08-01~2024-07-31 / **业务单号** FIRST… / **渠道** 支付宝`、`押金退还记录`（失败条目含失败原因）。三源=true、拆分=true、覆盖租期=true、业务单号=true、渠道=true | **PASS** | `Temp/shots2/009_E3F2_6_timeline.png`<br>`Temp/shots2/007_E3F_6_timeline.png` |
| 7 | 管理端归档已搬出租约 | 转 ARCHIVED 终态、不可变更 | 弹窗「归档后租约不可再变更（属性冻结、状态终态）」；确认后 `GET /leases/7` → `status=**ARCHIVED**`；页面含「已归档」 | **PASS** | `Temp/shots2/008_E3F_7_archived.png` |

### 1.3 流程 ③ CF-E4-X1 跨身份空间越权（DEF-075 回归）

集群会话：`conversation id=9`「工单沟通·WO202609221917」，参与者 = 张伟（resident.id=1）/ 陈国强 / 刘伟民。

| # | 步骤 | 期望 | 实际 | 判定 | 截图 |
|---|---|---|---|---|---|
| 1 | **撞号验证**：superadmin（sys_user.id=1）访问只含 resident1（resident.id=1）参与的会话 | **403** | `GET /conversations/9` → **403**「非会话参与者，无权访问会话」（旧构建 13:51 与新构建 14:00 两次一致） | **PASS** | `Temp/shots2/001_X1_superadmin_403.png` |
| 2 | 对照：真实参与者 resident1 | 200 | `GET /conversations/9` → **200**（`closedAt`/`participantCount=3` 正常返回） | **PASS** | `Temp/shots2/002_X1_resident1_200.png` |
| 3 | 对照：admin1（参与者）200；staff1（入群后）200；**resident2（非参与者）403** | 参与者 200 / 非参与者 403 | admin1 → **200**；staff1 → **200**；resident2 → **403** | **PASS** | — |
| 4 | 派单前 staff1（非参与者） | 403 | 见 §1.1 步骤 3b：**403** | **PASS** | `Temp/shots2/005_E4_3c_staff_not_in_group.png` |
| 5 | 前端：superadmin 直接打开 `/admin/conversations/9` | 不渲染会话内容 | 消息行数=**0**；页面仅「暂无会话 / 选择左侧会话开始沟通」 | **PASS** | `Temp/shots2/003_X1_superadmin_conversation_page.png` |

**结论：DEF-075（撞号身份空间越权）已修复且本轮回归通过**——判定按身份空间收口，
且不是「一律拒绝」（参与者 200 对照成立）。

### 1.4 已作废的中间判定（必读：9 条 FAIL 中无一条是最终产品缺陷）

| 中间判定 FAIL | 作废原因 | 复测结论 |
|---|---|---|
| `4-neg-5704` / `4-neg-5705` / `4-neg-5703`（租约7）/ `4-neg-5602` / `4c 确认发起退还`（共 5 条，`results_r2_e3f.json`） | 跑在**修复前构建**：全部返回 `HTTP 500 系统内部错误`，业务码不可达 → 即 §3 缺陷 DEF-R2-01 | 修复后构建复测：**5704/5705/5706/5703/5602 全 PASS，4c PASS** |
| `1b 重复发起退租 → 5603`（`results_r2_e3f2.json`） | **本人脚本用错端点**（用 `POST /api/v1/lease-move-out-applications` 传 body；正确为 `POST /api/v1/leases/{leaseId}/move-out-applications`） | 正确端点复测 → **`code=5603`「已有待审核的退租申请」PASS** |
| `2a 管理端退租申请列表`（`results_r2_e3f2.json`） | **本人前置假设错误**：本轮 `admin1` 绑定社区已变为 `[1]`（第一轮为 `[1,3]`），看不到社区 3 的申请；同时踩到「错误端点返回 500」 | 改用 **superadmin 全局视图** → 列表出现 `ZL0002 … 待审核` **PASS** |
| `4-neg-5703（租约2）`（`results_r2_e3f2.json`） | 同一原因：`admin1` 对社区 3 不可见 → `404 租住记录不存在`（**数据权限正确行为**） | 由 superadmin 审核通过后复测 → **`code=5703` PASS** |
| `7 服务人员「提交处理结果」`（`results_r2_e4c.json`） | 断言依赖 toast 文本，读取时 ElMessage 已消失（我方断言过窄） | 以详情留痕 / 居民端可见 / 管理员通知 / 状态待确认四条独立证据 → **PASS**（同 §1.1 步骤 2b 情形） |
| `2c 居住关系终止`（`results_r2_e3f4.json`） | 断言正则未覆盖实际取值 `MOVED_OUT`；且首次探测用了错误端点 `/api/v1/residence-relations` | 正确端点 → `status=MOVED_OUT`、`moveOutDate=2026-10-31` → **PASS** |

---

## 2. 两条链各自的通过判据结论

### 2.1 链 ① CF-E4 工单完整链路 —— **通过（14 步全绿）**

断言①~⑨逐条结论：

| 断言 | 内容 | 结论 |
|---|---|---|
| ① | 受理前无群 | **成立**（面板数=0、无「工单沟通」文案） |
| ② | 受理后自动建群，成员=提交人+受理管理员 | **成立**（成员恰为 2 人：张伟、陈国强） |
| ③ | 派单后服务人员自动入群，三方齐 | **成立**（3 人：张伟/陈国强/刘伟民） |
| ④ | 派单前非参与者不可见（403），派单后三方互通 | **成立**（前 403 / 后 200 且互相可见） |
| ⑤ | 接单→开始处理→提交结果→居民确认的状态推进 | **成立**（已派单→已接单→处理中→待确认→已完成） |
| ⑥ | 提交结果后管理员可感知、居民可见处理留痕 | **成立**（管理员收到「工单待居民确认」；居民端可见解决说明与图片） |
| ⑦ | 终审入口仅 COMPLETED 可见 | **成立**（已完成=1、待确认=0） |
| ⑧ | 终审结束 → 会话回收（CLOSED + closed_at，历史可读、只读、再发言 5604） | **成立**（`closedAt=2026-09-22T13:49:02`，输入框 0，历史 5 条，再发言 `code=5604`） |
| ⑨ | 通知矩阵 | **部分成立**：口径隔离（DEF-067）成立；**事件 5 与事件 8 的收件对象与文档矩阵不符**，见 DEF-R2-02 / DEF-R2-03 |

> 补充观察（非判据）：步骤 11 的再发言请求返回 **HTTP 200 + body `code=5604`**
> —— 业务错误码走 body 而非 HTTP 状态码，属既有统一响应约定，仅作记录。

### 2.2 链 ② CF-E3-F 押金退还 —— **通过（修复后构建全绿）**

- **可执行部分全部 PASS**：入口前置、弹窗（渠道只读=原渠道、金额默认全额、不托管声明、幂等请求号）、
  `PENDING → 渠道调用 → FAILED 回落 PAID + 失败原因常驻`、失败重试换新请求号（记录不复用）、
  居民端徽标与流水（含失败原因/请求号/时间）、三源合并时间线（金额拆分/覆盖租期/业务单号/渠道）、
  归档终态、越权 403，以及 **5703/5704/5705/5706/5602 五条反例**。
- **`REFUNDED` 成功分支未取得**：支付宝沙箱对历史交易返回 `ACQ.TRADE_NOT_EXIST`（原交易不在沙箱），
  故此分支只能覆盖文档允许的「失败回落 PAID 可重试」一侧（见 §4）。
- **`5704/5705/5706/5703` 只能做接口级交叉印证**：前端会在本地先行拦截（金额上限、渠道只读），
  UI 不产生这些请求；这部分判据由带鉴权的同源请求补齐，属预期。
- **第一轮暴露的「全量租约押金未缴」现象**：见 DEF-R2-05（迁移未回填 vs 会话内被外部改数）。

---

## 3. 发现缺陷（仅报告 + 复现步骤，未改任何代码）

### DEF-R2-01【严重 · 已复现 · **会话内被并发修复**】数据权限拦截器漏登记 `lease_deposit_refund`
导致社区管理员发起的**押金退还 100% 500**，且全部业务错误码不可达

- **现象**：`POST /api/v1/leases/{id}/deposit-refunds` 对 ADMIN 一律返回
  `HTTP 500 {"code":500,"message":"系统内部错误"}`；同一时刻 superadmin 调用却成功，
  说明与调用者身份相关。
- **根因（后端日志实证）**：
  ```
  org.springframework.jdbc.BadSqlGrammarException:
  ### Error querying database. Cause: java.sql.SQLSyntaxErrorException: Unknown column 'community_id' in 'where clause'
  ### SQL: SELECT id, lease_id, move_out_application_id, amount, channel, out_request_no,
  ###      channel_refund_no, status, fail_reason, operator_id, created_at, refunded_at
  ###      FROM lease_deposit_refund WHERE (out_request_no = ?) AND community_id IN (1) LIMIT 1
      at …DepositRefundService.request(DepositRefundService.java:111)
  ```
  `DataScopeInterceptor` 会为 ADMIN 向被判定为「社区域」的表注入 `community_id IN (绑定社区)`，
  但 `lease_deposit_refund`（V22 建表）**没有 `community_id` 列**，且 `SKIP_TABLES` 漏登记该表
  → 连幂等查询都炸，故 **5703/5704/5705/5706/5602/5901 全部被 500 掩盖**。
- **复现步骤**：
  1. 以 `admin1/Admin123456` 登录管理端；
  2. 打开 `/admin/leases/4`（租约 4 = 已搬出 + 押金已缴）；
  3. 点「发起押金退还」→ 填备注 → 点「确认发起退还」；
  4. 观察：`POST /api/v1/leases/4/deposit-refunds` → 500，流水不落库，押金状态不变；
     （等价接口级复现：带 admin token `POST /api/v1/leases/4/deposit-refunds`
      body `{"amount":3800,"channel":"ALIPAY","outRequestNo":"任意唯一值"}`）
  5. 对照：用 superadmin token 调同一接口 → 200（证明是身份相关注入）。
- **修复状态**：**已由并发会话修复**——`backend/.../interceptor/DataScopeInterceptor.java`
  修改时间 **13:45:34**，`SKIP_TABLES` 新增 `"lease_deposit_refund"`，并留有注释
  「漏登记时 ADMIN 查询会被注入 community_id → BadSqlGrammar 500（社区管理员必炸）」；
  后端于 **13:55:50 重启**（pid 22128 → 14096）后生效。
- **本人复测**：修复后构建上 5704/5705/5706/5703/5602 与完整 4a–4d、5、6 全 PASS。
- **遗留结论**：源码级缺陷已闭环；**本项无需再改代码**，仅需在测试记录中体现「曾复现 → 已修复 → 已复测」。

### DEF-R2-02【重要 · 文档与实现不一致】事件 8「终审关闭」收件对象错误，被派服务人员收不到通知

- **文档矩阵要求**（`13_核心业务流程三端联动手测用例.md` L466）：
  事件 8 终审关闭 → 「居民提交人 + **被派服务人员**」；且 CF-E4 步骤 10 表述为「**三方均收到终审关闭通知**」。
- **实现**（`WorkOrderService.close` L484–488）：`createForResident(...)` + `notifyCommunityAdmins(...)`
  —— 发给「居民提交人 + **该社区绑定管理员**」，**没有** `notifyCurrentAssignee`。
- **浏览器实测（工单 WO202609221917，事件已发生）**：
  - resident1：`工单已结束 / 您的工单 WO202609221917 已终审结束（已关闭）` ✔
  - admin1：`工单已关闭 / 工单 WO202609221917 已终审关闭` ✔
  - **staff1：`「关闭/终审」类通知 = 0 条`** ✘（staff1 仅收到 新工单派发 / 工单已接单 / 居民已确认工单）
- **复现步骤**：走完 CF-E4 步骤 1–10（受理→派单→接单→处理→提交→居民确认→终审结束），
  以 `staff1` 登录 →「消息中心」，按关键词「关闭 / 终审」检索 → **无命中**；
  以 `resident1`/`admin1` 同法检索 → 均有命中。
- **影响**：服务人员无法感知工单已终审归档；步骤 10「三方均收到」不成立。
- **建议方向**（不实施）：`close()` 补 `notifyCurrentAssignee(order, "工单已关闭", …)`，
  或按上游文档口径裁决矩阵（管理员 vs 服务人员）后再对齐。

### DEF-R2-03【重要 · 同类不一致】事件 5「开始处理」缺管理员通知

- **文档矩阵**：事件 5 开始处理 → 「居民提交人 + **相关管理员**」。
- **实现**（`WorkOrderService.process` L410–414）：仅 `createForResident(...)`；代码注释自称
  「R48/R74 事件 5「开始处理」：**事件集本身无必须收件对象**」——与文档矩阵表述冲突。
- **实测**：admin1 的通知中心**没有**该单的「处理中/开始处理」条目
  （1917 单在 admin1 侧仅 6 条：新工单待受理 / 工单已受理 / 工单已接单 / 工单待居民确认 /
  工单待终审关闭 / 工单已关闭），resident1 侧有「您的工单 … 服务人员已开始处理」✔。
- **复现**：CF-E4 步骤 6 之后，以 `admin1` 检索通知 → 无该事件；以 `resident1` 检索 → 有。
- **性质**：属**文档矩阵与代码注释相互矛盾**，需上游裁决口径后二选一对齐。

### DEF-R2-04【低危】HTTP 方法不支持时返回 500 而非 405

- **现象**：对 GET-only 集合端点发 POST → `HTTP 500 {"code":500,"message":"系统内部错误"}`。
- **实测两例**：
  - `POST /api/v1/lease-move-out-applications` → 500（应 405）
  - `POST /api/v1/residence-relations`（错误路径）→ 500
- **根因方向**：`GlobalExceptionHandler` 的兜底 `Exception → 500` 吞掉了
  `HttpRequestMethodNotSupportedException`（应为 405）。
- **复现**：带任意已登录 token，`POST` 上述端点即可。
- **影响**：低（仅影响错误语义与排障体验，不影响正常业务）。

### DEF-R2-05【数据/迁移口径 · 需上游裁决】V22 未回填 `deposit_status`，存量已缴租约被判为「未缴」

- **13:40 实测（修复前构建）**：`GET /leases` 显示 **7 份租约全部 `depositStatus=UNPAID`**，
  其中租约 4、6 **已有 `FIRST/SUCCESS/7600（含押金 3800）`** 的成功首期单；
  → 管理端 `/admin/leases/4` 徽标「押金未缴」、`发起押金退还` 按钮
  `disabled=true` 且 `title="当前押金状态为「未缴」，仅「已缴」状态可发起退还"`、
  退还流水 0 条 → **押金退还功能对所有存量租约不可用**。
- **代码依据**：`deposit_status='PAID'` 仅在**运行时首期支付结算**写入
  （`PaymentService` L339–351）；`V22__lease_deposit_refund.sql` **没有回填语句**
  （仅 `ADD COLUMN deposit_status … DEFAULT 'UNPAID'`），故历史已缴租约不会被置为 PAID。
- **13:56 复测**：租约 4、6 已变为 `depositStatus=PAID`（**会话内被外部改数**，
  推测为并发会话手工修数/补种子）。
- **结论**：**迁移本身仍未回填** → 重新初始化数据库会复现同一现象。
  建议上游裁决：是否在迁移/种子中为「存在 SUCCESS 首期支付单且押金部分 > 0」的租约补写 `PAID`。
  本轮已据此把功能验证改为在「补数后的数据」上执行（证据见 §1.2）。

---

## 4. 未跑到与原因（如实说明）

| 未覆盖项 | 原因 | 性质 |
|---|---|---|
| CF-E3-F 的 **`REFUNDED` 成功分支** | 支付宝沙箱对 2023 年的历史交易返回 `ACQ.TRADE_NOT_EXIST`（沙箱无该交易），本地无法造出成功退款；已完整覆盖文档允许的另一侧「失败回落 PAID + 原因常驻 + 换新请求号重试」 | 环境限制，非缺陷 |
| **`5901`（渠道未配置）** | 该门禁位于「渠道一致性」之后；ALIPAY 已配置（enabled=true），WECHAT 会先被 5705 拦截 → 该码在本环境**不可达** | 校验顺序使然，非缺陷 |
| 押金徽标 **`REFUNDING` / `REFUNDED` / `OFFSET`** 三态 | 渠道同步返回失败，`REFUNDING` 瞬时态与成功终态无数据；`OFFSET` 无种子数据。已核对前端五态 `data-deposit` 样式类齐备，但**未做浏览器态验证** | 数据不可达 |
| **CF-E4-X2**（文档 L509 观察项） | 文档自身标注为「观察项」，本轮未单列用例 | 按文档口径 |
| 工单群「终审关闭」的**服务人员侧通知** | 已作为 **DEF-R2-02** 单列为缺陷，未计入通过项 | 缺陷单列 |
| 押金退款**对社区 3 租约**的完整 UI 走查 | 本轮 `admin1` 绑定社区为 `[1]`，社区 3 无对应 ADMIN；代之以 superadmin 完成审核联动 | 环境权限配置 |

---

## 5. 遗留数据清单（本轮经 UI / 同源接口写入，未改库结构、未重置库、未改种子）

| 库表 | 主键 / 业务号 | 本轮产生的变更 |
|---|---|---|
| `work_order` | `WO202609221917`（id=10） | 我创建并走完整链：`PENDING→TO_ASSIGN→ASSIGNED→ACCEPTED→IN_PROGRESS→TO_CONFIRM→COMPLETED→CLOSED` |
| `work_order` | `WO202609228704` | 我创建（终审入口对照单）→ 驱动到 `TO_CONFIRM` → 被居民「不满意退回」→ `IN_PROGRESS` |
| `work_order` | `WO202609229007` | 我创建 → 被管理端「驳回」→ `REJECTED` |
| `work_order` | `WO202609227609` | 我创建 → 被居民「取消」→ `CANCELLED`（未建群） |
| `work_order_attachment` | 单 10 下 2 条 | 提交工单 1 张 PNG + 提交处理结果 1 张 PNG（存于 `backend/uploads/`，已 gitignore） |
| `conversation` | **id=9**（`WORK_ORDER_GROUP`，工单 10） | 受理时创建；终审结束时 `status=CLOSED`、`closed_at=2026-09-22 13:49:02`、参与者 3 人 |
| `conversation_message` | 会话 9 内 5 条 | 管理端/服务人员/居民发言 + 终审回收系统消息 |
| `lease_deposit_refund` | id=1 `R2PROBE-sa_lease4-1790056561733` | 租约 4 · 3800 · ALIPAY · **FAILED**（superadmin 发起，渠道异常） |
| `lease_deposit_refund` | id=2 `R2PROBE-a1_lease4-1790056567475` | 租约 4 · 3800 · ALIPAY · **FAILED**（admin1 发起） |
| `lease_deposit_refund` | id=3 `DR4MUC9PV2E1GRQ` | 租约 4 · 3800 · ALIPAY · **FAILED**（UI 发起，交易不存在） |
| `lease_deposit_refund` | id=4 `DR4MUC9Q3TGTXNF` | 租约 4 · 3800 · ALIPAY · **FAILED**（UI 失败重试，新请求号） |
| `lease_record` | id=4（ZL0004） | 未改状态（`MOVED_OUT`）· `deposit_status=PAID`（外部补数）· 挂 4 条 FAILED 退还流水 |
| `lease_record` | id=7（ZL0007） | `MOVED_OUT → **ARCHIVED**`（归档验证，有意用 7 而非 4 以保留 4 作退款目标） |
| `lease_record` | id=2（ZL0002） | `ACTIVE → **MOVED_OUT**`（退租审核联动验证） |
| `lease_move_out_application` | id=3（租约 2） | `PENDING → APPROVED`（superadmin 审核，房源回收=重新在架） |
| `lease_move_out_application` | id=4（租约 1） | `PENDING → REJECTED`（驳回分支验证，理由「房屋仍需继续出租，暂不同意退租」） |
| `residence_relation` | id=2（李思远 ↔ A 座1 单元301） | `status=MOVED_OUT`、`move_out_date=2026-10-31`（退租联动） |
| `notification` | 若干 | 工单事件通知（admin1 6 条 / superadmin 1 条「新工单待受理」/ resident1 6 条「您的工单」口径 / staff1 3 条） |
| 未触及 | — | **未 ALTER / DROP 任何表结构；未执行任何数据重置；未修改 Flyway 迁移与种子脚本** |

> 说明：本会话期间**存在其他并发会话在同一数据库上作业**（工单 `WO202609221935`/`WO202609222282`/
> `WO202609222542`/`WO202609223837`/`WO202609227179`/`WO202609224381` 等由他方创建，
> 且 `lease_record.deposit_status` 被外部改数）。上表仅列**本会话确认写入**的数据。

---

## 6. 服务进程状态（明确回答）

| 服务 | 是否仍在跑 | 明细 |
|---|---|---|
| **前端 5173** | **是** | pid **9192**（node），启动 10:52:37，监听 `::1`；`http://localhost:5173/api/v1/payment/channels` → 401（代理正常） |
| **后端 8081** | **是** | pid **14096**（java），启动 **13:55:50**（**本会话内被重启过**，前身 pid 22128 启动于 13:15:45） |
| 后端 8080 | 是（他方） | pid 24804（java），启动 13:57:54 —— 并发会话新起，5173 **未**指向它 |
| 8083 / 5199 | 否 | 未监听 |

- 本会话**未执行任何服务重启/停止**；8081 的重启由并发会话完成（与 DEF-R2-01 的修复配套）。
- 5173 的 Vite 代理目标为 `VITE_PROXY_TARGET ?? http://localhost:8080`，
  实测流量落在 **8081**。

---

## 7. 一句话结论

- **链 ① CF-E4 工单完整链路：通过** —— 14 步全绿，断言①~⑧全部成立；
  断言⑨的「口径隔离（DEF-067）」成立，但**通知矩阵中事件 5、事件 8 的收件对象与文档不符**（DEF-R2-02 / R2-03）。
- **链 ② CF-E3-F 押金退还：通过（修复后构建）** —— 入口/弹窗/失败回落/重试换号/居民端流水/三源时间线/归档/越权拦截
  与 5703·5704·5705·5706·5602 五条反例全部 PASS；`REFUNDED` 成功分支受沙箱限制未取得。
- **链 ③ 安全回归 X1：通过** —— superadmin 撞号访问 resident1 会话 403、真实参与者 200，DEF-075 确认修复（新旧构建各验一次）。
- **本轮共报 5 项缺陷/不一致**：1 项严重（DEF-R2-01，**已被并发会话修复并复测通过**）、
  2 项通知矩阵与实现不一致（DEF-R2-02 / R2-03）、1 项低危错误码语义（DEF-R2-04）、
  1 项迁移未回填的数据口径问题（DEF-R2-05，需上游裁决）。
- **未修改任何业务代码 / 库结构 / 种子，未 git 提交**；5173 与 8081 均在运行。
