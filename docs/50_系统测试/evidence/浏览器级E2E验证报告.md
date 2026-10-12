# 浏览器级端到端（E2E）验证报告

> **本文件已按文档纪律 10（测试证据一律入库）从 `Temp/` 移入 `docs/50_系统测试/evidence/` 入库**；
> 内容与 `Temp/` 原件一致，仅：①在下方补一行**权威来源说明**（纪律 8）；
> ②把证据目录口径改为「**截图本地留证、不入库**」。**入库时间**：2026-09-22（用户裁决）。

- **权威来源（纪律 8，2026-09-22 补）**：被测环境为**真前端 `http://localhost:5173` + 真后端 `http://localhost:8081` + 真数据库 `community_residence_test`**（非桩、非模拟）；驱动为 **Playwright 1.63.0 + 本机 Microsoft Edge 153.0.4234.48（`channel:'msedge'`，未使用 msedgedriver）**；执行时间 **2026-09-21 夜间 ~ 2026-09-22 00:40**（本机时钟）；被测用例集为 `docs/50_系统测试/13_核心业务流程三端联动手测用例.md`（CF-E1~CF-E12）。
- **项目**：`D:\code\community-residence-service-platform`
- **执行角色**：浏览器级 E2E 验证工程师（只读业务代码，未改任何业务代码，未 git 提交）
- **执行时间**：2026-09-22 00:05 ~ 00:40（本机时钟）
- **被测入口**：`http://localhost:5173`（经父会话修复代理后）
- **权威依据**：`docs/50_系统测试/13_核心业务流程三端联动手测用例.md`（CF-E1~CF-E12）
- **用例总量**：**60 步** → **PASS 50 / FAIL 6 / INFO 2 / SKIP 2**（6 个 FAIL 中 **3 个是同一真实缺陷的复现**，另 3 个为断言口径问题，已在下文更正）
- **报告与明细入库路径**：本文件 = `docs/50_系统测试/evidence/浏览器级E2E验证报告.md`；60 行明细 = `docs/50_系统测试/evidence/浏览器级E2E-60步明细.md`（原 `Temp/e2e-browser/consolidated_table.md`）。
- **截图证据（122 张 PNG）＝本地留证、不入库**：截图仅用于**人工复核与缺陷复现**（本文各表的「代表截图」列与 §③ 缺陷条目引用的路径均为原 `Temp/shots/*.png`）；`Temp/` 已在 `.gitignore` 内，**截图与原始 JSON / 脚本一律不入库**（避免仓库体积膨胀，纪律 10 例外项）。**若要复核截图，请在原执行机的 `Temp/shots/` 目录按本文路径查看**；本文引用的截图路径即为清单，不再另行枚举 122 个文件名。
- **用例级原始 JSON 与脚本（不入库）**：`Temp/e2e-browser/results_*.json`、`Temp/e2e-browser/*.mjs`。

---

## ① 权限 / 浏览器可用性确认

**结论：浏览器自动化在本会话完全可用。** 未申请任何提权（审批提示已禁用），未依赖 `msedgedriver`。

### 1.1 父会话基线复跑（原始输出）

```
=== 复跑探测（最终确认） ===
edge 版本: 153.0.4234.48
进程存活: True (pid=12872)
CDP /json/version -> Browser=Edg/153.0.4234.48
webSocketDebuggerUrl=ws://127.0.0.1:9334/devtools/browser/3fd33e40-0503-44c1-8500-b33613a03fc3
```

命令（与父会话基线同构）：

```powershell
$edge = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
$p = Start-Process $edge -ArgumentList '--headless=new','--remote-debugging-port=9334',
     "--user-data-dir=$env:TEMP\edge-cdp-probe-sub2",'--no-first-run','about:blank' -PassThru
```

### 1.2 采用的驱动方式（推荐路线，未走 CDP 手撸）

Playwright 1.63.0 + **本机 Edge channel**（Playwright 自管驱动，不需要 `msedgedriver.exe`，规避 153.0.4234.48 与缓存 driver 143/152 的版本错配）：

```
=== Playwright channel:'msedge' 复跑 ===
playwright OK, title= 登录 · 社区居住服务管理系统 url= http://localhost:5173/auth/login
```

- 安装：`Temp/e2e-browser/` 内 `npm i playwright@1.63.0`（自带 chromium 未下载，全程用 `channel:'msedge'`）
- 多身份隔离：每个账号一个独立 `browserContext`（等价于「隐身窗口」，互不顶号）
- 探测进程已清理：`剩余探测进程: 0`，`9334 listening: False`
- 全程 headless，真实点击 / 真实输入 / 真实路由跳转；断言一律用**页面可见文本**，API 抓包仅作交叉印证

### 1.3 环境阻塞与处置（已由父会话修复）

父会话初始状态下 5173 不可用于浏览器验证：

| 现象 | 原始证据 | 结论 |
|------|---------|------|
| 5173 的 `/api` 全部 502 | `http://localhost:5173/api/v1/payment/channels` → **502 Bad Gateway** | Vite dev server（旧 PID 7036）启动时无 `VITE_PROXY_TARGET`，`vite.config.ts` 回落到 `http://localhost:8080`，而 8080 无监听（后端在 8081） |
| 后端本身正常 | `http://localhost:8081/api/v1/auth/admin/login` → **200**（admin1 签发 token） | 缺陷在代理配置，不在后端 |
| 5173 只绑 IPv6 | `5173 LISTEN ... addr=::1`；`127.0.0.1:5173` connection refused | 必须以 `http://localhost:5173` 访问 |

- 我当时的处置：在 **127.0.0.1:5199** 另起独立 Vite 实例（带 `VITE_PROXY_TARGET=http://localhost:8081`）先跑通流程 1~3，并立即上报父会话。
- 父会话修复 5173（新 PID 26256）后，我 **kill 掉 5199 并在 `http://localhost:5173` 上完整复跑流程 1~3（12/12 PASS）**；5199 现为 `NOT LISTENING`。
- 最终所有 60 步证据均产自 **`http://localhost:5173`**。

---

## ② 逐流程结果表

> 完整 60 行（含「期望 / 实际 / 截图路径」）见 `Temp/e2e-browser/consolidated_table.md`。
> 下表为按流程聚合的结论；「实际」列摘录关键页面可见文本。

| 流程 | 步骤 | 期望 | 实际（页面可见文本摘录） | 结论 | 代表截图 |
|------|------|------|------------------------|------|---------|
| **F1 三端登录** | `resident1` 登录 | 进入居民端首页 | `URL=/resident/home`，页面「张伟（居民）｜首页｜公共资源｜我的预约｜租住与房源｜我的工单｜我的反馈｜公告｜消息」 | PASS | `Temp/shots/001_login_resident1.png` |
| | `admin1` 登录 | 进入管理端看板 | `URL=/admin/dashboard`，「陈国强（社区管理员）｜运营看板｜阳光花园社区」 | PASS | `Temp/shots/002_login_admin1.png` |
| | `staff1` 登录 | 进入服务人员工作台 | `URL=/staff/dashboard`，「刘伟民（服务人员）｜工作台｜今日概览｜待接单 0｜处理中 1」 | PASS | `Temp/shots/003_login_staff1.png` |
| **F2 管理端导航** | 侧栏菜单清点 | 只有一个「租住与房源」一级项 | admin1 侧栏 **13 项**（无「系统」）：看板/社区管理/工单调度/人员排班/服务人员/居民/**租住与房源**/公告/反馈/预约/评价/消息中心/消息会话；superadmin **14 项**（多「系统」）。命中「租住/房源/退租/协议」的项 **仅 1 个** | PASS | `Temp/shots/004_admin_sidebar.png` |
| | 五 Tab 切换 | 入住申请/租约管理/房源管理/退租申请/协议模板 均可切换且渲染内容 | 五 Tab 全部可点，`?tab=applications\|leases\|housings\|moveout\|agreements` 逐一切换成功，各面板标题与副题正确（「居民入住申请审核…」「租约属性 · 待签约与承租确认…」「房屋与房源一体化挂牌…」「居民自助退租申请审核」「自定义租赁协议：正文占…」） | PASS ×5 | `Temp/shots/005_tab_入住申请.png`、`006_tab_租约管理.png`、`007_tab_房源管理.png`、`008_tab_退租申请.png`、`009_tab_协议模板.png` |
| **F2b 分组不重置（回归）** | 租约管理选分组 → 切房源管理 → 切回租约管理 | 分组选择**不被重置** | 选前 `?tab=leases&stage=toSign` → 中途 `?tab=housings&stage=toSign` → 返回 `?tab=leases&stage=toSign`；`stage` 参数保持一致=true，返回后仍命中「待签约」 | **PASS** | `Temp/shots/011_lease_group_after_return.png` |
| **F3 入住申请可见性（管理端）** | Hub 默认 Tab = 入住申请 | 可见待审/历史申请 | 默认 URL 不带 `tab` 参数（设计口径）；表格显示「申请人｜身份｜目标房屋｜申请时间｜状态｜操作」及历史行（已撤回/已通过） | PASS | `Temp/shots/012_admin_applications_default_tab.png` |
| **F3 入住申请可见性（居民端）** | 「租住与房源 → 入住申请」 | 本人申请（4 态）+ 撤回入口 | **注意**：`resident1` 该 tab 初始为空态（「还没有入住申请记录」），4 态与撤回**当日无法验证**；已通过 UI 造数补齐 → 见 F3b | 见 F3b | `Temp/shots/013_resident_applications.png` |
| **F3b 居民端申请 4 态 + 撤回** | `resident1` 提交申请 | 状态「待审核」 | 提交后列表：「2 号楼1 单元101｜申请时间 2026-09-22 00:28｜申请身份 租客｜**待审核**｜撤回申请」 | PASS | `Temp/shots/003_F3b_3_resident_application_list.png` |
| | 点「撤回申请」 | 二次确认 → 「已撤回」 | 弹窗「撤回入住申请：撤回后将终止『2 号楼1 单元101』的入住申请审核…」→ toast「已撤回该入住申请」→ 刷新后状态**已撤回** | PASS | `Temp/shots/005_F3b_5_after_withdraw.png` |
| | 管理端可见 | 管理端默认 tab 可见该条 | 表格行：「张伟 租客 2 号楼1 单元101 2026-09-22 00:28 **已撤回** -」 | PASS | `Temp/shots/006_F3b_6_admin_applications.png` |
| **F4 租住闭环主链** | ① 居民 `resident2` 看待签约租约 | 卡片「待签约」+「确认承租」 | 「翠湖名庭社区 1 号楼1 单元401｜2026-10-15 ~ 2027-10-14｜¥3,200/月｜押金 ¥3,200｜**待签约**｜确认承租」 | PASS | `Temp/shots/001_F4_1_resident2_before_confirm.png` |
| | ② 点「确认承租」 | 二次确认 + 留痕 | 弹窗「确认承租『1 号楼1 单元401』（租期 2026-10-15 ~ 2027-10-14，月租 ¥3,200）？确认后由社区管理方发起租赁协议…」→ toast「已确认承租，等待社区管理方发起租赁协议」→ 刷新后卡片变**已确认承租** | PASS | `Temp/shots/003_F4_1_after_confirm.png` |
| | ③ 管理端 `admin2` 复核 | 承租确认列变「已确认」 | 待签约分组行：「ZL0003 李思远 ID 2 … 未发起协议 **已确认** 待签约」 | PASS | `Temp/shots/004_F4_2_admin_tosign_row.png` |
| | ④ 管理方「发起协议」 | 选模板 → 发起成功 | 详情页「承租确认 已确认 · 2026-09-22 00:13」；弹窗「发起租赁协议」选中「住宅租赁合同（社区标准模板）（默认）」→ toast「协议已发起，等待双方确认」；`POST /lease-agreements` → `status=PENDING` | PASS | `Temp/shots/005_F4_3_after_create.png` |
| | ⑤ 管理方确认 | 转「单方已确认」，**租约仍未生效** | 弹窗「管理方确认协议…确认签署」→ toast「协议已确认」；抓包序列证明 `agreementStatus=PARTIAL` 时 `lease.status` **仍为 `TO_SIGN`** | PASS | `Temp/shots/006_F4_4_admin_confirmed.png` |
| | ⑥ 居民方确认 → 租约生效 | 双方齐 → `SIGNED` + `ACTIVE` | 弹窗「确认协议内容…我已阅读并同意」→ toast「协议已确认」；刷新后卡片「**协议已签署 在租** 待支付首期费用」；抓包 `SIGNED` + `status=ACTIVE` | PASS | `Temp/shots/008_F4_5_resident_confirmed.png` |
| | ⑦ 管理端复核 | 已生效 + 变更历史 | `/admin/leases/3`：「已签署｜承租已确认｜**在租**｜剩余 387 天」 | PASS | `Temp/shots/009_F4_6_admin_final.png` |
| **F4b 审核通过 → 待签约（CF-E3-A）** | ① `resident3`(王雅静) 提交申请 | 待审核 | 居民端列表「2 号楼1 单元101 … **待审核**｜撤回申请」 | PASS | `Temp/shots/002_F4b_2_resident3_pending.png` |
| | ② 管理端「通过」 | 填租约起止/月租/押金 | 表单值 `{开始:2026-09-22, 结束:2027-09-30, 月租:2800.00, 押金:2800.00}` → 表格状态变**已通过** | PASS | `Temp/shots/002_F4b_5_approved.png` |
| | ③ 租约状态 | **待签约**（非已生效） | 待签约分组：**「ZL0007 王雅静 ID 3 2 号楼1 单元101 阳光花园社区 2026-09-22 ~ 2027-09-30 未发起协议 **待确认** **待签约**」** | PASS | `Temp/shots/006_F4b_6_tosign_group.png` |
| | ④ 自动建群聊 | 生成 `LEASE_GROUP` 群聊 | 消息会话出现「**租约沟通·2 号楼1 单元101**｜租约群聊｜进行中」 | PASS（断言口径已更正，见 §③.4） | `Temp/shots/007_F4b_7_conversations.png` |
| | ⑤ 未确认承租拦截 | 「发起协议」置灰 + 原因 | 按钮 `disabled=true`，页面「承租确认 居民尚未确认承租——确认后方可发起租赁协议」（后端对应 5601） | PASS | `Temp/shots/005_F4b_8_tenancy_blocked.png` |
| | ⑥ 居民端卡片 | 待签约 + 确认承租 | 「阳光花园社区 2 号楼1 单元101｜2026-09-22 ~ 2027-09-30｜¥2,800/月｜**待签约**｜确认承租」 | PASS | `Temp/shots/006_F4b_9_resident3_tosign.png` |
| | ⑦ 居住关系建立 | 审核通过建居住关系 | 居住关系页（选居民 王雅静）：「王雅静｜2 号楼1 单元101｜2026-09-22｜-｜**居住中**｜搬出登记」 | PASS | `Temp/shots/002_F4d_2_relations.png` |
| **F5 首期支付弹窗 DEF-068** | ① 打开入口 | 已生效未付租约有入口 | ZL0001 卡片：「阳光花园社区 1 号楼1 单元101｜2025-07-01 ~ 2027-06-30｜¥2,800/月｜押金 ¥2,800｜协议已签署｜在租｜**待支付首期费用**」 | PASS | `Temp/shots/003_F5_1_before_open.png` |
| | ② 弹窗第一步 | 金额 = 月租 + 押金；渠道凭据门控 | 「支付首期费用 · 1 号楼1 单元101｜房屋｜月租 ¥2,800｜押金 ¥2,800｜**首期费用 ¥5,600** = 首月租金 ¥2,800 + 押金 ¥2,800」；渠道卡：支付宝（可用）／微信支付（**未配置**，置灰）→ `enabled=1` | PASS | `Temp/shots/004_F5_2_step1_channels.png` |
| | ③ 选渠道 → 去支付 | 进入第二步（**不付款**） | 「单号 **FIRST20260922801232**｜支付宝｜¥5,600｜首期费用」；新窗口被立即关闭（`popups=1`，**未发生任何真实付款**） | PASS | `Temp/shots/005_F5_3_step2_payment.png` |
| | ④ 关闭弹窗 | 关闭且不付款 | 弹窗不可见，租约卡片保持原状 | PASS | `Temp/shots/006_F5_4_dialog_closed.png` |
| | ⑤ **重开弹窗（DEF-068 核心）** | **不再停在「选渠道」，续接既有单直达支付页** | **「进入支付步=true｜续接提示可见=true」**：页面出现「**已续接您此前未支付的首期支付单，渠道以该单为准（支付宝）**」，仍为同一单号 `FIRST20260922801232` | **PASS** | `Temp/shots/007_F5_5_reopened.png` |
| | ⑥ 已付清租约收口 | 无重复支付入口 | `resident5` ZL0006：「协议已签署｜在租｜**首期已支付 ¥7,600 · 2026-01-01 10:22**」，**首期按钮数 = 0** | PASS | `Temp/shots/008_F5_6_resident5_paid.png` |
| **F6 到期门禁 DEF-069** | ① 已过期租约卡片 | 无首期入口 + 原因文案 | `resident4` ZL0005（2025-09-01 ~ 2026-08-31）：「**已到期**｜协议已签署｜**在租**｜**首期费用不可再支付：租约止期已过；如需继续租住请续租，或办理退租。**｜『已到期』为日期标注（止期已过），非状态流转，租约状态仍为『在租』」。**首期按钮数=0**，续租=1，申请退租=1 | **PASS** | `Temp/shots/001_F6_resident4_expired_card.png` |
| | ② 展开区 | 原因 + 日期标注说明 | 展开区含「到期说明 **已过期 22 天**」+ 同上原因与说明文案 | PASS | `Temp/shots/002_F6_resident4_expanded.png` |
| **F7 通知身份隔离 DEF-067** | ① 居民口径通知存在 | `resident1` 收到「您的…」 | 消息中心：「**工单已处理完成**｜**您的**工单 WO202609227776 已处理完成，请确认」；「**工单已派单**｜**您的**工单 WO202609227776 已派单给 刘伟民」，未读 2 | PASS | `Temp/shots/014_F7_resident1_notifications.png` |
| | ② **撞号验证（关键）** | `superadmin` **不得**出现居民口径通知 | `superadmin`（sys_user id=1，与 resident1 的 resident id=1 撞号）消息中心 = **「暂无通知消息」**；命中居民口径标记 = **空** | **PASS** | `Temp/shots/015_F7_superadmin_notifications.png` |
| | ③ 反向验证 | `resident1` 不得出现管理员口径通知 | 命中管理员口径标记 = 空 | PASS | `Temp/shots/014_F7_resident1_notifications.png` |
| | ④ 服务人员端 | 只看到自己的通知 | `staff1`：「**新工单派发**｜工单 WO202609227776 已派单给您，请及时接单」，未读 1 | PASS | `Temp/shots/017_F7_staff1_notifications.png` |
| | ⑤ `admin1` 管理员口径 | 应看到管理员口径通知 | `admin1` 消息中心 = **「暂无通知消息」**（本次业务动作未给他产生通知，见 §③.3） | INFO | `Temp/shots/016_F7_admin1_notifications.png` |
| **F8 工单三端交接** | ① 居民提交工单 | 待受理 | 填「水电管道 / E2E联调验证-厨房水龙头持续漏水 / 1 号楼 1 单元 101 厨房 / 13800000001」→ 列表出现「**WO202609227776｜待受理**」 | PASS | `Temp/shots/002_F8_1_submitted.png` |
| | ② 管理端待办 | 待处置出现 | 待处置 **1 单**：「**新单**｜WO202609227776｜E2E联调验证-厨房水龙头持续漏水」 | PASS | `Temp/shots/003_F8_2_admin_pending.png` |
| | ③ 派单 | 选服务人员 | 抽屉「候选按调度推荐档位排列」，推荐档「常驻本社区 · 擅长该类别 1 人」= **刘伟民**（常驻阳光花园+滨江雅苑，午班，在手工单 1）；其他档「王建军（未绑定、未排班）」→ toast「已派单给 刘伟民」 | PASS | `Temp/shots/006_F8_2_assigned.png` |
| | ④ 服务人员待办 | 待接单出现 | 「待接单 1｜E2E联调验证-厨房水龙头持续漏水｜已派单｜**接单**」 | PASS | `Temp/shots/007_F8_3_staff_todo.png` |
| | ⑤ 接单 | 已接单 | toast「接单成功，请尽快开始处理」 | PASS | `Temp/shots/009_F8_3_accepted.png` |
| | ⑥ 开始处理 | 处理中 | toast「已开始处理」 | PASS | `Temp/shots/010_F8_3_processing.png` |
| | ⑦ 提交处理结果 | 待确认 | 填「已更换水龙头密封圈并紧固接口，试水 10 分钟无渗漏。」→ toast「处理结果已提交，等待居民确认」 | PASS | `Temp/shots/011_F8_3_result_submitted.png` |
| | ⑧ 居民查看结果 | 可见处理结果 + 确认完成 | `/resident/work-orders/3` 含处理结果原文，按钮条「取消工单｜不满意｜**确认完成**｜评价」 | PASS | `Temp/shots/012_F8_4_resident_detail.png` |
| | ⑨ **居民确认完成** | 转「已完成」并出现评价区 | **toast「请求体格式不合法」，状态仍为「待确认」，无评价区** | **FAIL（真实缺陷 DEF-A）** | `Temp/shots/013_F8_4_confirmed.png` |

---

## ③ 发现的产品缺陷（只报告 + 复现步骤，未改任何代码）

### DEF-A（P0，阻断）居民端「确认完成」与「关闭工单」请求缺请求体 → HTTP 400，工单永卡「待确认」

**同一根因、两处表现。**

- **根因（静态定位）**
  - 后端 `backend/.../workorder/controller/WorkOrderController.java:142-144`：`PATCH /{id}/confirm` 声明 `@RequestBody @Valid WorkOrderActionDTO dto`（**必填请求体**）；`:158-160` 的 `/{id}/close` 同样。
  - 前端 `frontend/src/api/workorder.ts:85-92` 把 body 声明为**可选**（`data?: { remark?: string }`）。
  - 调用点未传 body：`frontend/src/views/resident/WorkOrderDetailView.vue:188` `confirmWorkOrder(orderId)`；`frontend/src/views/admin/workorder/WorkOrderDetailView.vue:128` `closeWorkOrder(orderId)`。
  - 无 body → Spring 抛 `HttpMessageNotReadableException` → `GlobalExceptionHandler:113` → `badRequest("请求体格式不合法")`。
  - （对照：服务人员侧 `acceptWorkOrder/processWorkOrder` 均传 `{remark}`，故 F8 第 ⑤⑥ 步正常通过。）

- **复现步骤（浏览器）**
  1. `resident1 / Resident123456` 登录 → 我的工单 → 打开「WO202609227776」（状态 待确认）→ 点「**确认完成**」→ 弹窗「确认服务已完成并符合预期吗？」→ 点「确认完成」。
  2. 观察右下角 toast：**「请求体格式不合法」**；工单状态**仍为「待确认」**，页面**不出现「服务评价」区**。
  3. （第二处）`admin1` → 工单调度 → 近期完成 → 打开「WO202609100002」（已完成）→ 点「**关闭工单**」→ 确认。
  4. 同样 toast **「请求体格式不合法」**，工单**无法归档为「已关闭」**。

- **抓包证据（两处均实测）**
  ```json
  {"status":400,"url":"/api/v1/work-orders/3/confirm","method":"PATCH",
   "body":"{\"code\":400,\"message\":\"请求体格式不合法\",\"data\":null,...}","reqBody":""}
  {"status":400,"url":"/api/v1/work-orders/2/close","method":"PATCH",
   "body":"{\"code\":400,\"message\":\"请求体格式不合法\",\"data\":null,...}","reqBody":""}
  ```
  （`reqBody` 为空串 ⇒ 确实未发送请求体）

- **影响**
  - CF-E4 第 6 步（居民确认）**不可达** → 工单永久停留在「待确认」，服务人员侧也无法收口；
  - CF-E4 第 7 步与 **CF-E8 整条「服务评价与不满意跟进」链路不可达**（评价仅在「已完成」状态露出）；
  - 管理端「关闭工单」终态**不可达**，工单无法归档。
- **建议（供决策，未实施）**：二选一——后端把 `confirm`/`close` 的 `@RequestBody` 改为 `required = false`（或统一给这两个端点允许空体）；或前端两处调用改为传 `{}`（`confirmWorkOrder(orderId, {})` / `closeWorkOrder(orderId, {})`）。
- **证据**：`Temp/shots/001_DEF_confirm_400.png`、`003_DEF_close_400.png`；原始 JSON `Temp/e2e-browser/results_def_workorder.json`

---

### DEF-B（P2，文档-实现不一致）管理端无「受理」入口，CF-E4 第 2 步「待受理 → 待派单」无处执行

- **文档依据**：`../13_核心业务流程三端联动手测用例.md` CF-E4 第 2 步 —— 「管理端 `admin1`：『工单调度』找到该工单 → **受理** → 状态 **「待派单」**」。
- **实际**：管理端工单**列表与详情均无「受理」动作**。管理端列表动作按钮实测为 `派单 / 详情`；详情页「管理操作」面板（`WorkOrderDetailView.vue:297-311`）仅渲染 `派单|改派工单`、`驳回工单`、`关闭工单`；`frontend/src/api/workorder.ts` 中也不存在管理端受理端点（`acceptWorkOrder` 是服务人员「接单」，`ASSIGNED→ACCEPTED`）。工单从「待受理」**直接经「派单」跳到「已派单」**，「待派单」状态在 UI 上无独立入口。
- **复现步骤**：
  1. `resident1` 提交一条新工单（状态「待受理」）。
  2. `admin1` → 工单调度 → 待处置 → 该单只有「派单 / 详情」两个动作，**找不到「受理」**。
  3. 直接点「派单」→ 工单变为「已派单」（跳过「待派单」）。
- **证据**：`Temp/e2e-browser/results_f7_f8.json` 中 `F8 / 2b`（INFO 行，含当时页面全部可见按钮）；`Temp/shots/003_F8_2_admin_pending.png`、`004_F8_2_admin_detail.png`
- **说明**：按 50 阶段职责边界，此条**只记录现象、不判定「是否满足需求」**；是否补「受理」入口由 60 评估验收/决策决定。

---

### DEF-C（P2，文档-实现不一致）居民提交工单未给管理端产生任何通知，且 `admin1` 通知中心为空

- **文档依据**：CF-E4 第 1 步「…管理端出现待办**并收到通知**」；`13` 号文档 §4 速查表「居民提交工单 → 管理端『待受理』待办 **+ 通知**」；CF-E11 第 1 步（在线 ≤3s 推送）。
- **实际**：`resident1` 提交工单后，管理端**待办出现**（PASS），但 **`admin1` 消息中心 = 「暂无通知消息」**，全程未收到任何通知。
- **静态印证**：`WorkOrderService.submitWorkOrder` 只写 `work_order` + `work_order_process`，**无 `notificationService` 调用**；全后端 `createForUser` 的工单相关调用点仅两处——派单通知**被派单人**（`:324`，即 staff1）与 `:542` 通知当前处理人，**没有任何一处通知社区管理员**。
- **复现步骤**：
  1. 记下 `admin1` 消息中心内容（当前：「暂无通知消息」）。
  2. `resident1` 提交一条工单。
  3. 等 ≥10s 后刷新 `admin1` 消息中心 → **仍为「暂无通知消息」**（待办侧在工单调度里可见）。
- **证据**：`Temp/shots/016_F7_admin1_notifications.png`、`003_F8_2_admin_pending.png`；`results_f7_f8.json` 的 `F7 / 3`

---

### DEF-D（P3，体验/观察项）首期支付弹窗：3s 轮询后「已在新窗口打开支付宝」的引导会回退为「凭据暂未就绪」

- **现象**：第 ③ 步进入支付页时，弹窗先显示「**已在新窗口打开支付宝，请在支付宝页面完成付款**」；约 3 秒后（首轮 `getPaymentStatus` 轮询回写 `payment`）文案变为「**支付页凭据暂未就绪，请点击下方「重新获取支付页」重试**」，尽管支付页其实已经打开过。
- **静态印证**：`LeaseFirstPaymentDialog.vue:89-93` 的 `credentialMissing` 依赖 `payment.alipayForm`；而 `pollOnce()`（`:276-281`）用查单接口结果整体替换 `payment.value`，**查单接口不返回 `alipayForm`/`qrCode`**，故凭据字段被抹掉、引导文案翻转。
- **复现步骤**：`resident1` → 租住契约 → ZL0001 →「待支付首期费用」→ 选支付宝 →「去支付」→ **保持弹窗不动观察 5 秒**，可见文案从「已在新窗口打开支付宝」变为「支付页凭据暂未就绪」。
- **缓解**：弹窗提供了「重新获取支付页」按钮，用户仍可恢复，故非阻断。
- **证据**：`Temp/shots/005_F5_3_step2_payment.png`（截图时刻即为回退后的文案）

---

### §③.4 断言口径更正（**非产品缺陷**，避免误读 consolidated_table 中的 FAIL 行）

`consolidated_table.md` 中有 3 行 FAIL 属于我的断言写得过窄/选择器用错，**实际行为正确**，特此更正：

| 原始 FAIL 行 | 更正结论 |
|--------------|---------|
| `F4b / 5 审核通过自动创建租约群聊` | **实际 PASS**。会话已创建：「租约沟通·2 号楼1 单元101｜租约群聊｜进行中」。原断言错误地要求会话**列表**里出现居民姓名，而列表只显示群标题（`租约沟通·<房屋位置>`）。 |
| `F4b-补验 / 1 群聊成员含居民与管理员` | **不可断言（非缺陷）**。管理端会话页不展示成员名单，仅能确认群聊已建、可打开、可发送。 |
| `F4b-补验 / 3 居住关系建立` | **实际 PASS**，已在 `F4d / 2` 复验：「王雅静｜2 号楼1 单元101｜2026-09-22｜居住中」。前一次是页面要求先在**居民选择器**中选人，我未选。 |

---

## ④ 未跑到 / 未验证项与原因

| 项 | 状态 | 原因 |
|----|------|------|
| CF-E3-G 第 4 步「**当天到期**租约仍可支付首期」 | **未跑到** | 数据集中**没有止期=当天**的租约（现有止期：2026-08-31 已过、2026-10-11、2026-12-31、2027-06-30、2027-09-30、2027-10-14）。造这条数据需改库/改种子，超出只读约束。 |
| CF-E3-G 第 6 步「到期提醒定时任务按 `LeaseReminder` 去重触发」 | **未跑到** | 需触发/等待 01:30 定时任务；本会话不重启服务、不改库，无法安全触发。仅确认「即将到期」标注与排序正确（ZL0002 剩余 19 天，分组「即将到期」并带角标 `1`）。 |
| 居民端「入住申请」**已通过 / 已驳回** 两态的居民侧展示 | **部分未跑到** | 已通过态由 `resident3`(王雅静) 链路覆盖（申请→已通过→生成待签约租约→居住关系）；**已驳回**态未制造（避免再增数据）。已撤回/待审核两态已实测。 |
| CF-E3-D 第 5 步「已支付后再发起 → **5904**」 | **UI 不可达（非失败）** | `resident5` 的 ZL0006 已付清首期，卡片显示「首期已支付 ¥7,600」，**首期支付入口按设计被隐藏**，因此 UI 上无法触发 5904。5904 仅为「客户端状态陈旧」时的兜底分支。 |
| CF-E3-F 第 4~6 步（押金在线退还 / 租约明细时间线） | **未跑到（文档已标待实现）** | `13` 号文档明确标注 R72/R73「仅有设计、尚未实现」，属「跳过（待实现）」。 |
| CF-E5/E6/E7/E8/E9/E12（公告、反馈、资源预约、评价、看板、游客看房） | **未跑到** | 超出本轮优先级 1~8；且 CF-E8 因 DEF-A 被**硬阻断**（评价仅「已完成」可用，而工单无法到达「已完成」）。 |
| 附件上传（图片）链路 | **未跑到** | 超出本轮范围；工单提交/处理两步我刻意未上传图片，避免向 `./uploads` 落文件。 |

---

## ⑤ 遗留数据清单（全部经 UI 产生，供人工清理）

> 均为**测试副作用**；未直接改库、未改种子。后端连的是干净测试库 `community_residence_test`。

| # | 库表 | 主键 / 标识 | 说明 | 建议清理动作 |
|---|------|------------|------|-------------|
| 1 | `residence_application` | **id=17**（resident 1 张伟 → 2 号楼1 单元101） | 我为验证「4 态 + 撤回」创建，现状态 **WITHDRAWN**；备注「E2E 浏览器验证用申请，验证后由人工清理」 | 删除该行 |
| 2 | `residence_application` | **id=18**（resident 3 王雅静 → 2 号楼1 单元101） | 我为验证「审核通过→待签约」创建并**审核通过**，现状态 **APPROVED**；备注「E2E 审核通过链路验证，验证后由人工清理」 | 删除该行 |
| 3 | `lease_record` | **id=7（ZL0007）** 社区1 / 租户 王雅静(r3) / 2 号楼1 单元101 / 2026-09-22~2027-09-30 / ¥2,800 | 由 #2 审核通过联动生成，现状态 **TO_SIGN**（待签约），承租确认「待确认」 | 删除该行（并清理其 conversation 关联） |
| 4 | `residence_relation` | 王雅静 ↔ 房屋 **ID 1177**（2 号楼1 单元101），入住日期 2026-09-22，状态**居住中** | 由 #2 审核通过联动建立 | 删除该行 |
| 5 | `conversation` | 标题「**租约沟通·2 号楼1 单元101**」，类型 `LEASE_GROUP`，状态 `进行中` | 由 #2 审核通过自动创建（+ 对应 `conversation_member`） | 删除该会话及其成员行 |
| 6 | `lease_record` | **id=3（ZL0003）** | **被修改**：`status` TO_SIGN → **ACTIVE**；`agreement_status` NONE → **SIGNED**；`tenant_confirm_at=2026-09-22T00:13:47`、`tenant_confirm_by=2` | 如需恢复基线：置回 `TO_SIGN`/`NONE` 并清空承租确认字段 |
| 7 | `lease_agreement` | **id=3**（lease_id=3） | 我为验证签约链路发起并双方确认，状态 **SIGNED**（管理方确认人：赵敏） | 删除该行 |
| 8 | `work_order` | **id=3（WO202609227776）** | 我为验证工单三端交接创建，**卡在 `TO_CONFIRM`（待确认）**——因 DEF-A 无法确认完成 | 删除该行；并删除其 `work_order_process` 流转记录（SUBMIT/ASSIGN/ACCEPT/PROCESS/COMPLETE） |
| 9 | `lease_payment` | **paymentNo = `FIRST20260922801232`**（lease_id=1，`payment_type=FIRST`，¥5,600，`channel=ALIPAY`，**status=PENDING**） | DEF-068 验证产生（**未真实付款**） | ⚠️ **注意**：该 PENDING 单会让 `resident1` 的 ZL0001「首期支付」走**幂等续接**而非新建单。若要恢复「干净首期支付」基线需删除此行；若要复现 DEF-068 则**保留**它 |
| 10 | `notification` | 本次新增若干行 | 含：`RESIDENT` 主体 id=1 的「工单已派单」「工单已处理完成」；`USER` 主体 staff 的「新工单派发」；租约/协议/入住申请审核相关行 | 可选清理（不影响流程） |
| 11 | `sys_operation_log` | 本次新增若干行 | 上述所有增改动作的操作留痕（**设计上仅追加**，建议保留） | 建议保留 |

**清理注意**：`residence_application`(id=18) / `lease_record`(id=7) / `residence_relation` / `conversation` 之间存在联动关系，建议一并清理（并留意 #3 与 `house` 1177 的状态是否需回滚）。

---

## ⑥ 服务与端口现状（明确回答）

| 端口 / 进程 | 现状 | 说明 |
|------------|------|------|
| **5173（前端）** | ✅ **仍在运行** | `LISTEN pid=26256 name=node addr=::1`（父会话修复后的实例）。经代理访问 `http://localhost:5173/api/v1/payment/channels` → **401**（已正确转发到后端，不再是 502）。⚠️ 只绑 `::1`，请用 `http://localhost:5173`，**`127.0.0.1:5173` 会 refused** |
| **8081（后端）** | ✅ **仍在运行** | `LISTEN pid=10516 name=java addr=::`；直连 `http://localhost:8081/api/v1/payment/channels` → **401**（端点需鉴权，服务正常） |
| 8080 | ❌ 未监听 | 后端不在 8080 |
| 5199（我的临时实例） | ✅ **已关闭** | `NOT LISTENING`（父会话修好 5173 后立即 kill） |
| 9333 / 9334（探测用 CDP） | ✅ **已清理** | 我的 `edge-cdp-probe-sub*` 进程已全部 killed（剩余 0），端口未监听 |

**我未重启、未另起实例占用 5173/8081，未重置库，未改种子脚本，未改任何 `backend/`、`frontend/src/` 业务代码，未 git 提交。**

---

## 附：证据文件索引

| 文件 | 内容 |
|------|------|
| `Temp/e2e-browser/consolidated_table.md` | **全部 60 步**的「流程/步骤/期望/实际/结论/截图」明细表 |
| `Temp/e2e-browser/results_f1_f3.json` | 三端登录 + 侧栏/五 Tab + 分组不重置 + 申请可见性（12 行） |
| `Temp/e2e-browser/results_f3b.json` | 入住申请 4 态与撤回（3 行） |
| `Temp/e2e-browser/results_f4.json` | 租住闭环主链 8 步（含 API 状态迁移序列） |
| `Temp/e2e-browser/results_f4b.json` / `results_f4c.json` / `results_f4d.json` | 审核通过→待签约→群聊→居住关系（含更正说明） |
| `Temp/e2e-browser/results_f5_f6.json` | DEF-068 首期支付续接 + DEF-069 到期门禁（8 行） |
| `Temp/e2e-browser/results_f7_f8.json` | 工单三端交接 + DEF-067 通知身份隔离（17 行，含四端通知中心全文） |
| `Temp/e2e-browser/results_def_workorder.json` | DEF-A 两处 400 的原始抓包 |
| `Temp/e2e-browser/lease_truth.json` / `stages.json` / `recon*.json` | 6 条租约全量台账与各端页面侦察原文 |
| `Temp/shots/*.png` | 122 张截图 |
