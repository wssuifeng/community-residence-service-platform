# 18项原异常专项的逐项处置

| DEF | 严重度 | 本轮处置 | 证据与范围 |
|---|---|---|---|
| DEF-103 | 致命 | 沿用工程证据与用户金融确认 | 用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。；../db-final-reconciliation-20261019/defect-ledger.json |
| DEF-104 | 严重 | 本轮补证处置 | 真实十路提交、十路审核，只一单/一条状态日志/一条居民通知且重复请求幂等；本批补锁后最新终态读取，并以reject-concurrency.json追加十路驳回/通知一次验证；old-anomalies.json, reject-concurrency.json |
| DEF-106 | 严重 | 本轮补证处置 | 真实十路租约建群全部返回同一ID；唯一组1；本批修复旧快照重查及撞键锁升级；old-anomalies.json |
| DEF-107 | 严重 | 沿用工程证据与用户金融确认 | 用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。；../db-final-reconciliation-20261019/defect-ledger.json |
| DEF-108 | 严重 | 本轮补证处置 | 实际状态DTO接受TO_SIGN，PENDING→TO_SIGN成功；old-anomalies.json |
| DEF-110 | 严重 | 本轮补证处置 | 关闭租约群发起看房5604，预约零新增；old-anomalies.json |
| DEF-112 | 严重 | 沿用工程证据与用户金融确认 | 用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。；../db-final-reconciliation-20261019/defect-ledger.json |
| DEF-115 | 一般 | 本轮补证处置 | 非交易同目标状态竞争、仅一条留痕；首期生效按现行FIRST设计，未执行付款/协议激活交易；remaining-anomalies.json |
| DEF-116 | 一般 | 本轮补证处置 | 真实十路受理群/留痕/通知各一次；修复CAS输家旧快照误拒，当前读返回赢家群，合法重复状态仍5302；结合工程分支验证；remaining-anomalies.json |
| DEF-117 | 一般 | 本轮补证处置 | 真实撤回与审批三轮竞争仅一方终态；弱/强关联关闭边界采用本轮ResidenceApplicationLeaseLinkTest；remaining-anomalies.json |
| DEF-118 | 一般 | 本轮补证处置 | 真实会话无关房源400/零新增及日期倒序；结合7项归属工程回归；remaining-anomalies.json |
| DEF-119 | 一般 | 本轮补证处置 | 真实补回链不覆盖TO_SIGN且不重开CLOSED群；条件更新和重复参与者采用本轮工程回归；old-anomalies.json |
| DEF-122 | 一般 | 本轮补证处置 | 本轮会话组件/列表定向回归，失败只读、直通后选中等原判据；frontend-targeted.json |
| DEF-123 | 一般 | 沿用工程证据与用户金融确认 | 用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。 手动激活的旧预期已由FIRST支付生效取代，本轮只验证手动路径被拒。；../db-final-reconciliation-20261019/defect-ledger.json |
| DEF-124 | 一般 | 本轮补证处置 | 本轮管理/居民工单组件定向回归，确认框未决防重与退回提示；frontend-targeted.json |
| DEF-127 | 一般 | 本轮补证处置 | 新增双行分页断言，六种关联selectBatchIds各一次且不逐行查关联；符合原spec层判据；backend-targeted.json |
| DEF-129 | 一般 | 沿用工程证据与用户金融确认 | 用户已明确本人确认可用即可，AI不复测；沿用逐项既有修复/已运行工程证据，不新增渠道异常通过结论。；../db-final-reconciliation-20261019/defect-ledger.json |
| DEF-130 | 一般 | 本轮补证处置 | 真实早于起租/晚于止租均业务码400且无申请；HTTP200统一响应不误作未拒绝；old-anomalies.json |

DEF-073与DEF-128两个微信一般保留沿用既有裁决，不新增微信交易；其余历史缺陷继续采用db逐项依据。本批新登记213～215另见08。

本轮将原18项逐条落到实际验证/现行设计替代/用户授权的金融证据采用，不再留一个未分解的“18项待整理”。工程层、真实HTTP/SQL与用户确认分开记载；未声称每条原异常均有真实渠道执行。
