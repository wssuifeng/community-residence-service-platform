"""回写dc实际结果；只能在补证结果和环境回收证据齐备后运行。"""
from pathlib import Path
import json,re
OUT=Path(__file__).resolve().parent;ROOT=OUT.parents[3];DOC=ROOT/'docs/50_系统测试'
def load(name):return json.loads((OUT/name).read_text('utf-8'))
def read(path):return path.read_text('utf-8-sig')
def write(path,text):path.write_text(text.rstrip()+'\n','utf-8')
def append(path,heading,body):
 s=read(path);assert heading not in s,heading;write(path,s+'\n\n'+heading+'\n\n'+body)
summ=load('summary.json');perf=load('performance.json');base=load('performance-base.json');bulk=load('performance-bulk.json');data=load('bulk-data.json');realtime=load('realtime-browser.json');environment=load('environment-after.json');cleanup=load('cleanup.json');unit=load('backend-targeted.json');front=load('frontend-targeted.json')
assert cleanup['ownProcessesStopped'] and environment['normalProcessesPreserved'];assert summ['financialCommands']==0
p95base=[x['P95ms'] for x in base['labels'].values()];p95bulk=[x['P95ms'] for x in bulk['labels'].values()]
maxdom=max(max(x['conversationDOMMs'],x['notificationDOMMs']) for x in realtime['samples']);maxpush=max(x['conservativeRequestStartToTerminalMs'] for x in perf['pushSamples']);limits=load('redis-fault.json')
result_table=f"""| 验证范围 | 实际结果 | 证据 |
|---|---|---|
| 接口契约 | 10组、11个真实操作通过，补核sinceSeq非零过滤 | [契约抽样](evidence/dc-targeted-closeout-20261019/contracts.json) |
| 上传、通知渠道 | Edge 18项通过；图片/文档异常路径、两种勾选状态的发布与SQL留痕 | [界面与留痕](evidence/dc-targeted-closeout-20261019/browser.json) |
| Redis降级与恢复 | 独立6387实例断开超过5分钟，19项通过；轮询、登出黑名单、恢复补拉均复验 | [故障窗口](evidence/dc-targeted-closeout-20261019/redis-fault.json) |
| 预约冲突 | 21项检查通过，两类预约各3轮10路竞争，实际有效记录各仅1条 | [并发与边界](evidence/dc-targeted-closeout-20261019/concurrency.json) |
| 角色与数据权限 | 113项通过；本人、社区、身份空间、附件与反馈WS有正反向检查 | [权限矩阵](evidence/dc-targeted-closeout-20261019/permissions.json) |
| 性能与规模 | 基础/N7两轮各50 HTTP＋50 WS，60秒升载＋600秒稳态；P95分别{min(p95base)}～{max(p95base)}ms / {min(p95bulk)}～{max(p95bulk)}ms，错误0 | [量化对照](evidence/dc-targeted-closeout-20261019/performance.json) |
| 在线时延 | 真实Edge通知/反馈各10样本，最大{maxdom:.1f}ms；负载下6个STOMP样本最大{maxpush:.1f}ms，均≤3s | [页面计时](evidence/dc-targeted-closeout-20261019/realtime-browser.json) |
| 工程定向回归 | 后端{unit['tests']}项、前端{front['tests']}项通过，后端打包成功 | [后端](evidence/dc-targeted-closeout-20261019/backend-targeted.json)、[前端](evidence/dc-targeted-closeout-20261019/frontend-targeted.json) |"""
report=f"""# 系统测试报告 · 收尾审阅稿

> 2026-10-19 dc。评估编码对30系统设计及用户已批准修正的符合程度；需求满足性仍交60。主报告只保留结论、结果和审阅事项，详细依据见[39台账](39_最终用例与缺陷处置台账.md)。

## 1. 本轮结论与版本

收尾专项补证、发现问题修复、18项原异常专项逐项处置及文档整理已落实。建议采用“历次可追溯执行证据＋现行变更定向验证＋本轮缺项补证＋用户已确认范围”审阅本报告。未把累计证据写成305项在本轮最终源码整册重放，原03的98%/95%/P0 100%阈值不变；正式出口签署留待用户审阅。

本批从`caae28d`开始，新增DEF-213～215并补修DEF-104/106/116复验暴露的遗漏，最终缺陷登记共215项。实际测试使用独立8087/5181、独立数据库`community_residence_dc_20261019`和Redis6387；Flyway V43、61张业务表。运行包SHA256、各轮源码范围见[最终隔离基线](evidence/dc-targeted-closeout-20261019/final-runtime.json)；性能轮次对应包见[性能基线](evidence/dc-targeted-closeout-20261019/performance-tested-runtime.json)，最终复核仅补并发写入分支与工程整理，七类GET路径未变；Redis故障完整窗口的独立包另有[对应基线](evidence/dc-targeted-closeout-20261019/redis-tested-runtime.json)，后续并发修正未改变认证/通知实现。

正常8084/5176保持用户确认过的原运行包；本批修复尚未加载正常后端。2026-10-19收尾只读核查正常库仍V43/61、日志阈值730。隔离进程已停止、合成测试库及本机原始性能数据保留供复核；未操作用户浏览器。金融不复测，支付/查单/退款请求0。论文仍按整体首版完成后启动。

## 2. 实际结果

{result_table}

两轮均现场完成200个居民账号登录，50条WS连接保持在线；注册居民之外的角色权限用113项功能矩阵验证，未宣称为混合角色性能负载。N7规模为{data['after']['community']}社区、{data['after']['house']}房屋、{data['after']['work_order']}工单，每社区至少1000套。7类接口的P90/P95/P99、错误数及直方图在附件可复算；大体积JTL只留本机，摘要保存SHA256。

## 3. 修复与缺陷处置

| 项目 | 原问题与本批处理 | 验证 |
|---|---|---|
| DEF-213 | 同居民直通会话并发产生多个群；创建前锁居民行，随后读取加锁后的最新参与记录 | 居民及管理员交错10路调用返回同一ID，结合工程回归 |
| DEF-214 | Redisson包装的连接故障漏进降级分支，有效登录被误拒；统一识别传输故障，命令/序列化错误仍抛出 | 五分钟真实断开/恢复，退出令牌仍由DB拒绝，未放宽认证 |
| DEF-215 | Redis恢复后序号低于故障期序号，增量补拉遗漏；恢复数据库序号下限，故障期连续分配，Redis原子递增前抬升下限 | 同毫秒、重启/恢复工程回归，真实故障后的sinceSeq补拉 |
| DEF-104 / DEF-106 / DEF-116 | 原修复在真实竞争下仍有退租审批/驳回误拒、租约群重查失败及工单受理误读旧状态；补读取锁后最新终态，租约建群先锁父行 | 十路退租提交/审批/驳回、租约建群及工单受理通过；无重复副作用 |

18项原异常专项已有[逐项处置表](evidence/dc-targeted-closeout-20261019/18-defect-dispositions.md)：13项追加非交易定向证据；DEF-103/107/112/123/129按用户“本人确认可用即可”的授权，采用既有修复工程记录与金融确认，不新增渠道异常执行结论。DEF-123旧“手动生效”预期已由FIRST支付生效设计取代。DEF-073和DEF-128两个微信一般项继续按既有裁决保留，不要求重新支付/退款。

## 4. 原03出口逐项对照

| 原准则 | 本报告可证明的范围 | 审阅口径 |
|---|---|---|
| 执行率≥98% | 305项均有逐项来源，本批为23项追加证据；旧预期替代/弃置/人工确认分别保留 | 待审阅累计证据采用方式；不编造一次最终整册执行率 |
| 通过率≥95%，P0 100% | P0/P1/P2为104/179/22，本批专项及受影响回归通过，历史结果不抹除 | 同上；不能以单测数换算TC分母，当前未签署数值结论 |
| 致命/严重全关闭，一般≥90% | 分层采用口径为致命8/8、严重51/51、一般113/115（98.26%）；其中金融5项是既有工程＋用户确认采用，非本轮渠道异常通过 | 采用方式和两个微信保留项一起审阅；严格新增实测范围逐项见39 |
| N1/N2/N3 | 两轮最新7类接口P95≤2s，50 HTTP及50真实WS在线；DOM双通道各10样本≤3s | 本轮量化补证成立，保留负载、角色与样本边界 |
| 冲突与越权100% | 当前实际竞争/边界与113项权限矩阵全部通过，原判据按现行批准设计适配 | 指向实际矩阵分母，不扩大到所有HTTP方法任意组合 |
| 日志阈值730 | 正常库只读核查730，本批未更改正常阈值 | 证据成立 |
| 报告评审通过 | 本稿及附件已提交审阅 | 用户审阅后再记正式结论，50指针暂不前移 |

## 5. 保留观察与审阅记录

Redis故障期间通知业务响应约{next(c['actual']['seconds'] for c in limits['checks'] if c['name']=='Redis故障通知业务仍提交'):.1f}秒，功能和恢复判据通过，但异常窗口响应变慢如实保留。N7房屋列表P95由5ms变为8ms（+60%），没有达到04原“相对增幅≤30%”建议；其余接口变化见量化附件。两轮仍满足03的绝对2秒门槛，03未锁定该相对比例，本批不删改建议或用它替换03的绝对2秒门槛。测试主机结果不外推为生产多机容量结论。

本轮执行者已完成证据核对与环境回收；本地提交号见Git记录及最终交付消息，不推送。用户尚未审阅本稿；未填写“评审通过”，未关闭50、未开始60/70，也未启动论文。审阅关注点集中为：累计证据的采用方式、两个微信保留项及上述实测观察。依据与归档见[39](39_最终用例与缺陷处置台账.md)、[10](10_测试总结与归档.md)及[本批索引](evidence/dc-targeted-closeout-20261019/README.md)。
"""
write(DOC/'09_测试评估与报告.md',report)
write(DOC/'10_测试总结与归档.md',"""# 测试总结与归档

> 2026-10-19 dc。本轮技术收尾、资料核对和专用环境回收已执行；正式报告评审/阶段签署由用户完成。审阅正文统一进入[09报告](09_测试评估与报告.md)。

## 1. 本轮总结

缺项补证覆盖契约、上传、渠道两态、Redis故障恢复、量化负载、冲突和权限；18项原异常专项已有逐项采用依据。真实竞争与故障注入纠正了“工程回归通过就足以证明所有运行情形”的假设：本批发现并修复213～215，并补修104/106/116。失败初轮与脚本修正均保留来源，不覆盖成一开始全绿。

305 TC、215 DEF与85行RTM继续分别管理。脚本断言、单测项目、浏览器检查与TC定义不相加为总业务用例数。业务与金融已有确认继续有效；用户明确免于AI金融重测，本批金融请求0。

## 2. 归档资产

| 资产 | 位置与用途 |
|---|---|
| 简明审阅报告 | 09，含原03七项出口及尚未签署范围 |
| 最终台账 | 39；db原305/212逐项快照保留，dc追加23个TC证据及215项DEF处置 |
| 本批复核入口 | [dc索引](evidence/dc-targeted-closeout-20261019/README.md)，脚本、结构化结果、必要截图与原始摘要 |
| 历史证据 | cz/db/cv/cy等原目录保持，不修改早期执行结论或archive |
| 性能原始数据 | 本机output/runtime/dc-closeout，JTL不入库；公开摘要有SHA256和逐接口直方图 |
| 正常环境收尾核查 | [environment-after](evidence/dc-targeted-closeout-20261019/environment-after.json)，只读版本/计数/730与原进程保持 |
| 专用环境回收 | [cleanup](evidence/dc-targeted-closeout-20261019/cleanup.json)，仅本批8087/5181/6387进程；独立合成库保留 |
| 文档与敏感信息检查 | [document-verification](evidence/dc-targeted-closeout-20261019/document-verification.json)，区分新增问题与全局历史问题 |

## 3. 环境与交付

正常8084/5176、MySQL3306和Redis6379未重启；本批新后端只在隔离环境运行。隔离浏览器已关闭，用户浏览器未动。临时上传组件验证页已移除，测试令牌/服务日志/JAR/JTL留本机忽略目录，不提交凭据。保留本批独立合成库供审阅复核，不删除或清理任何历史output。

代码、文档和必要证据同批本地提交，不推送。提交号由最终交付消息与Git记录确定，避免把待提交稿写成已发布版本。用户审阅报告前不记阶段完成；审阅通过后再办理正式出口、正常运行包更新及后续阶段交接。
""")
# Keep the prior reconciliation as an explicitly dated historical subsection, then add current facts.
p=DOC/'39_最终用例与缺陷处置台账.md';s=read(p);s=s.replace('> 2026-10-19 db，','> **当前阅读顺序：先看§6 dc补证与处置，再按需查§1～5的db归并时点。** §1～5原缺证结论由§6追加证据承接，db原始快照不改。\n\n> 2026-10-19 db，',1);write(p,s)
append(p,'## 6. 2026-10-19 dc补证与逐项处置',f"""{result_table}

原量化/专项19项、3个缺项及1个部分证据项共23个TC已追加实际证据；完整305项对应见[追加台账](evidence/dc-targeted-closeout-20261019/case-supplement.md)。补齐的是所述子判据，未宣布所有历史TC的全部步骤在本轮重放。旧预期适配、弃置和人工确认仍保留。

212条原登记基础上新增213～215，共215条；原18项分别落到[处置表](evidence/dc-targeted-closeout-20261019/18-defect-dispositions.md)，不再留下一个未分解的18项待办。完整215项结构化去向见[DEF追加台账](evidence/dc-targeted-closeout-20261019/defect-supplement.json)。13项有追加非交易验证，5项金融采用既有工程与用户确认，不新增交易。一般微信073/128继续保留。

原有效产品分母190→193（新增3个严重）。新增定向证据之后，工程修复及回归依据175项；原人工接受11项；本轮金融采用5项；理由排除22项；微信保留2项，合计215。严格工程证据口径：致命7/8、严重49/51、一般106/115、轻微13/19；该数只描述工程证明层次，不能将金融采用说成未修。按本报告拟采用的“工程＋既有人工接受＋金融确认”口径：致命8/8、严重51/51、一般113/115、轻微19/19；金融并非真实渠道异常新执行，正式采用与出口签署见09审阅。

N7的逐接口相对变化如实留在performance.json；03阈值不改。原§4“最新量化未采集”的判断仅对db时点成立，本批已实际补测。累计TC执行/通过率仍不伪造，评审只针对明确的累计证据采用方式。""")
append(DOC/'07_测试执行记录.md','## 2026-10-19 dc定向补证与收尾',result_table+"\n\n原18项处置、新发现及初轮失败分别见08/39和dc索引。本轮不是305项整册重放，不相加不同层次的检查数；金融请求0。正常环境只读核查、专用环境已回收。")
append(DOC/'08_缺陷跟踪与回归.md','### 3.108 dc真实竞争、Redis故障与18项处置（2026-10-19）',"""| 编号 | 严重度 | 实际问题 | 修复与回归依据 |
|---|---|---|---|
| DEF-213 | 严重 | 同居民同社区DIRECT并发10路产生多个会话 | 居民父行锁＋锁后查询；首次失败与修复后10路/跨端混合竞争见dc并发/old-anomalies |
| DEF-214 | 严重 | Redis连接拒绝被Redisson包装，原catch不命中，有效登录误401、退出失败 | RedisFailures只识别传输故障；真实独立Redis五分钟故障19/19，DB黑名单兜底仍拒绝已退出令牌 |
| DEF-215 | 严重 | 故障期时间戳序号高于恢复Redis计数器，新通知sinceSeq漏拉 | 从DB恢复下限、本进程连续分配、Redis原子抬升并递增；故障后恢复补拉与相关单测通过 |

DEF-104、DEF-106本批复验重新暴露遗漏：前者在等待房屋锁后仍用旧PENDING申请判断已搬出租约，重复审核误5602；后者撞键重查留在旧事务快照、仅改锁后查询还会出现共享锁升级死锁。最终分别读取锁后申请终态、租约建群先取得父行锁再查；真实十路提交/审核和十路建群均通过，无重复副作用。最终复核另在DEF-104驳回和DEF-116工单受理分支复现同类旧快照误读：改为锁定当前读；驳回同目标重试返回原结果，工单CAS输家返回赢家群，开始时已受理的重复动作仍按原设计拒绝。两处分支各新增2个工程用例，真实十路驳回/受理全200、通知/群/留痕各一次。原始误拒与复核说明见dc的first-review和final-review-interpretation。此处沿用原编号，不重复新增同一缺陷。

18项原异常专项已逐条落到[采用依据](evidence/dc-targeted-closeout-20261019/18-defect-dispositions.md)，当前总215项见39§6。DEF-103/107/112/123/129采用已有工程记录及用户金融确认；未新增渠道异常实测。失败初轮、修复中间轮与脚本口径修正留在dc目录，不删除或改写历史db结果。正式出口评审见09。""")
# Current introductions; historical body stays intact.
for file,text in [
 ('02_需求跟踪矩阵.md','2026-10-19 dc：85行原映射保持，本轮23个TC追加证据及215项缺陷处置见39§6和dc台账；下方db/cz文字按原时点读取，未宣称305项本轮整册执行率。'),
 ('05_测试环境与数据.md','2026-10-19 dc：本批只使用community_residence_dc_20261019、8087/5181、Redis6387；V43/61。两轮性能及合成N7数据见dc证据，专用进程已回收、合成库保留。正常8084/5176与6379保持，收尾只读核查V43/61、730；以下早期端口/共享Redis约定均为历史记录。'),
 ('11_测试执行入口.md','2026-10-19 dc当前入口：09简明审阅报告；39§6为305 TC追加证据与215 DEF处置。专项补证和专用环境回收已执行，只剩正式报告审阅与出口签署。金融不复测；正常8084/5176保持原包，本批新修复尚未加载正常后端。下方da/db/cz按历史时点读取。')]:
 p=DOC/file;s=read(p);i=s.index('\n');write(p,s[:i]+'\n\n> **'+text+'**\n'+s[i:])
append(DOC/'02_需求跟踪矩阵.md','## dc追加执行对应（2026-10-19）',"""| R/N | 本轮追加证据 | TC对应 |
|---|---|---|
| N1/N2/N7 | performance基础/N7前后对照，50 HTTP＋50 WS，现场200居民登录 | NF-002/003/004/011 |
| N3、R30/R48 | realtime-browser各10样本；负载下6样本 | C6-004、C11-008/017、NF-005、SP-019 |
| N4 | Redis6387五分钟真实断开和恢复19项 | SP-022 |
| R33/R55 | 两类预约三轮10路、有效边界与SQL | SP-001～004 |
| R44/R45/N5 | 当前实际113项角色/社区/本人/身份空间/衍生通道 | SP-005～010 |
| R51/N10 | 真实通知渠道两态；10组接口/11操作抽样与上传异常 | C11-014、NF-016、SP-025 |
| R63/R66/R68/R70/R74 | 原18项逐项补证/按现行设计处置及金融证据采用 | 39§6、18-defect-dispositions，不伪造新增完整流程TC |

以上只追加本批实际范围；逐TC仍读取305项case-supplement与db原定义，85行RTM不改变需求满足性归60的职责边界。""")
status='**本阶段状态**：🔄 进行中。2026-10-19 dc技术补证、缺陷修复、18项逐项处置、09简明报告及环境回收已落实，原305 TC/85行RTM保持、DEF新增至215。最新性能/WS/冲突/权限有本轮实测；金融按本人确认及既有工程采用，不复测。正常8084/5176保持原包，本批新修复尚未加载正常后端；只读核查V43/61、730。当前只待用户审阅报告、确定累计证据采用并正式签署出口；不以单测或脚本数伪造305项整册执行率，原03阈值不改。不关闭50、不进入60/70、不启动论文；完整任务本地提交，不推送。'
p=DOC/'_中控.md';s=read(p);s=re.sub(r'\*\*本阶段状态\*\*：[^\n]+',status,s,count=1)
pos=s.index('### dc ·');s=s[:pos]+s[pos:].replace('- [ ]','- [x]');write(p,s)
# Phase pointer and startup summaries only; no frozen upstream document edits.
p=ROOT/'docs/00_总控.md';s=read(p);s=re.sub(r'\| 系统测试 \| `50_系统测试/` \| 🔄 \|[^\n]+', '| 系统测试 | `50_系统测试/` | 🔄 | 2026-10-19 dc已补齐专项/量化证据、修复213～215及104/106/116遗漏、逐项处置18项并形成09简明审阅报告；305 TC、215 DEF、85行RTM。正常运行包保持，金融本人确认不复测。当前待累计证据采用与正式报告/出口签署，03阈值未改，不自动进入60。 |',s,count=1)
s=re.sub(r'- 50 阶段当前动作（更新于2026-10-19）：[^\n]+','- 50 阶段当前动作（更新于2026-10-19 dc）：技术补证、缺陷处置、报告和专用环境回收已执行，审阅入口为09，详细依据39§6与dc索引。正常8084/5176原包保持，本批新修复尚未加载正常后端；本日只读核查V43/61、730。金融沿本人确认不复测；正式采用累计证据并签署前保留50指针，只本地提交、不推送。',s,count=1);write(p,s)
p=ROOT/'AGENTS.md';s=read(p);s=re.sub(r'> \*\*2026-10-19当前交接入口（db）\*\*：[^\n]+','> **2026-10-19当前交接入口（dc）**：先读总控/50中控，审阅入口09，依据39§6与dc证据。专项补证、最新性能/WS/冲突/权限、18项逐项处置及专用环境回收已执行；305 TC、215 DEF、85行RTM。新修复213～215及104/106/116已在隔离环境验证，正常8084/5176仍为用户确认原包；只读核查V43/61、730。金融本人确认即可、不复测；当前仅报告/累计证据采用及正式出口审阅，不伪造305项最终整册通过率。只本地提交、不推送、不自动关闭50或启动论文。',s,count=1);write(p,s)
p=ROOT/'README.md';s=read(p);s=re.sub(r'> \*\*2026-10-19 db收尾\*\*：[^\n]+','> **2026-10-19 dc收尾**：[09简明审阅报告](docs/50_系统测试/09_测试评估与报告.md)汇总真实补证、6处问题修复、18项逐项处置与保留观察；[39台账](docs/50_系统测试/39_最终用例与缺陷处置台账.md)维护305 TC/215 DEF/85行RTM。专用环境已回收，正常8084/5176原包保持；本批修复尚未加载正常后端。金融本人确认、不复测。当前等待报告及正式出口审阅，阶段只见总控/中控；以下带日期交接按历史读取。',s,count=1);write(p,s)
p=ROOT/'docs/90_全局/长任务路线图.md';s=read(p);s=re.sub(r'^4\. 金融专项可用性[^\n]+','4. 金融专项由用户确认可用，AI不复测。2026-10-19 dc已实际补齐定向专项、量化与旧缺陷处置，09为简明审阅入口，39§6为依据；正常运行包保持，本批修复尚未加载正常后端。当前只待报告的累计证据采用及正式出口审阅，不等同整体首版完成，不进入60/70或论文写作。',s,count=1,flags=re.M);write(p,s)
p=ROOT/'docs/90_全局/决策日志.md';s=read(p);heading='|------|------|------|-----------|';assert heading in s;s=s.replace(heading,heading+'\n| 2026-10-19 | **50 dc定向收尾**：用户授权AI完成待办后提交审阅；Redis原“用户停正常实例”方法改为独立6387真实五分钟停机/恢复，正常6379保持；性能仍50HTTP/200账号/600秒稳态并增50真实WS。金融按用户本人确认及既有工程证据采用，不追加交易；03阈值不改 | 实际补齐缺项并保留运行边界；修复直通/租约建群、重复退租审批与驳回、工单并发受理及Redis异常降级/序号恢复，不变更业务定义。正式出口待报告审阅，普通业务不整册重测 | 50中控、02/05/07～11/39、dc证据、README/AGENTS/总控 |',1);write(p,s)
print('updated current documents; formal phase exit remains unsigned')

# Refresh current runtime claims alongside the dc introduction; retain cz only as history.
p=ROOT/"README.md";s=read(p)
s=re.sub(r"> \*\*维护说明（2026-10-19）\*\*：[^\n]+",'> **维护说明（2026-10-19）**：本批从`caae28d`补充收尾修复，代码及文档同次本地提交；[最终隔离包](docs/50_系统测试/evidence/dc-targeted-closeout-20261019/final-runtime.json)、[性能测试包](docs/50_系统测试/evidence/dc-targeted-closeout-20261019/performance-tested-runtime.json)、[Redis故障测试包](docs/50_系统测试/evidence/dc-targeted-closeout-20261019/redis-tested-runtime.json)分别保留哈希。正常运行包仍沿用户确认版本。此前cz的`cf761e8`/`def6f90`/`57f26e9`分别是当时资料、源码及运行来源，按历史读取。',s,count=1)
s=re.sub(r"> \*\*最近运行核查为2026-10-18\*\*：[^\n]+",'> **最近运行核查为2026-10-19**：正常8084/5176、MySQL3306和Redis6379进程均保持；只读核查正常库V43/61、日志留存730天，见[本批核查](docs/50_系统测试/evidence/dc-targeted-closeout-20261019/environment-after.json)。隔离8087/5181/6387已停止，本批修复尚未加载正常后端；下方8080/5173是通用启动默认值。',s,count=1);write(p,s)
