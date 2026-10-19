# dc · 2026-10-19 定向收尾证据

主审阅入口为[09报告](../../09_测试评估与报告.md)，逐项总台账为[39](../../39_最终用例与缺陷处置台账.md)。本目录记录真实独立环境执行、初轮失败、修复和最终采用范围，不代替用户的正式报告评审。

## 1. 采用的结果

| 结果 | 内容与边界 |
|---|---|
| [summary.json](summary.json) | 305 TC、215 DEF及本批明确检查的独立计数；不合并不同统计单位 |
| [contracts.json](contracts.json) | 原NF-016规定的10组抽样，共11操作；method/path/参数/返回字段；不是211条path全部穷举 |
| [browser.json](browser.json) | Edge图片业务表单、原FileUploader组件的合法/非法/断网/重试、通知渠道UI保存→公告发布→SQL两态留痕，共18项 |
| [redis-fault.json](redis-fault.json) | 仅专用6387停机超过5分钟；HTTP轮询、界面、登出/DB黑名单与恢复WS/增量补拉，共19项 |
| [concurrency.json](concurrency.json) | 资源/看房各3轮10路竞争、有效边界矩阵；直通群归DEF-213，不冒称DEF-106租约群 |
| [permissions.json](permissions.json) | 五角色、本人与跨社区、身份空间、附件及反馈WS的113项实际矩阵；不是角色×全部HTTP方法的笛卡尔积 |
| [old-anomalies.json](old-anomalies.json) | 十路退租提交/审核、十路租约群、关闭群、回链、状态/日期、跨端直通及sinceSeq非零过滤 |
| [reject-concurrency.json](reject-concurrency.json) | 十路退租驳回全部幂等，通知仅一次、租约保持ACTIVE |
| [remaining-anomalies.json](remaining-anomalies.json) | 非交易状态迁移/工单受理/撤回审批竞争、房源归属与日期排序 |
| [realtime-browser.json](realtime-browser.json) | 真实Edge接收DOM通知与反馈各10次；HTTP发出到DOM出现为DB提交后时延的保守上界 |
| [performance.json](performance.json) | 基础/N7两轮同口径对照；每轮50 HTTP、50真实STOMP、60秒升载＋600秒稳态，200居民账号现场登录；负载中6次推送 |
| [performance-base.json](performance-base.json)、[performance-bulk.json](performance-bulk.json) | 分接口样本数、P90/P95/P99、错误、吞吐、毫秒直方图及原始JTL SHA256 |
| [bulk-data.json](bulk-data.json) | 仅独立库的5社区、每社区至少1000套、总5000套以上、年度10000工单以上 |
| [backend-targeted.json](backend-targeted.json)、[frontend-targeted.json](frontend-targeted.json) | 本轮实际执行类/文件、方法名、通过/失败/跳过；非全项目覆盖率 |
| [case-supplement.md](case-supplement.md)、[JSON](case-supplement.json) | 对305 TC逐项追加23个缺项/子判据证据，保留原基线与限制 |
| [18-defect-dispositions.md](18-defect-dispositions.md)、[完整DEF JSON](defect-supplement.json) | 13项非交易补证，5项金融按原工程与用户确认采用；其余212原去向及新213～215 |
| [environment-after.json](environment-after.json)、[cleanup.json](cleanup.json) | 正常进程/数据库/730只读核查；只回收本批8087/5181/6387进程 |
| [document-verification.json](document-verification.json)、[source-manifest.json](source-manifest.json) | 本批断链/敏感内容/历史保护/阶段指针检查、相对caae28d的只读审计与来源摘要 |

## 2. 环境与复现边界

专用数据库为`community_residence_dc_20261019`；后端8087、前端5181、Redis6387。本轮从`caae28d`开始；初始cx包与改后包分别见[startup](startup.json)、[最终运行包](final-runtime.json)，Redis完整故障窗口的包见[redis-tested-runtime](redis-tested-runtime.json)。性能对应包见[performance-tested-runtime](performance-tested-runtime.json)，最后仅补退租驳回/工单状态写入分支与工程整理，七类GET路径未改。最终源码与构建摘要由source-manifest和后端结果承接。正常8084/5176、Redis6379、正常库及用户浏览器保持。

支持脚本明确锁定独立库/端口，并拒绝支付、查单、退款URL。浏览器全局配置页可能显示支付卡片，该部分请求被阻断，实际金融请求0；不读取正常支付配置，不发起外部渠道动作。退租/状态竞争仅用本批独立库合成历史租约、押金0，不创建支付单。

脚本先后为：`start-isolated.py`创建独立环境，seed/finish初始化夹具；定向脚本执行；修复后`restart-isolated.py`只替换本批后端；故障窗口与性能错开；最后只读核查与cleanup。建库/种子脚本是本轮时点的执行记录，带存在性防护；不得在现存库重跑覆盖，复现需先选择新的独立库/端口并核对工具路径。正常运行包未替换，本目录不是部署命令。

私有JWT、密码注入文件、进程日志、JAR、原始JTL均在忽略的`output/runtime/dc-closeout/`。公开性能摘要可据直方图复算分位数，SHA256用于核对本机原始文件；不把原始大体积采样或凭据入库。原db证据与archive保持不改。

## 3. 失败初轮和口径修正

- `concurrency-first`、`old-anomalies-first/second`、`redis-fault-first/second`保留真实暴露的直通重复、退租重复审核误拒、租约群重查/锁升级、Redis降级失效和序号回退；修复依据分别登记213～215及原104/106。最后复核又在`remaining-anomalies-first-review`原响应中发现工单CAS输家误读PENDING，`reject-concurrency-first-review`复现退租驳回同类问题；分别沿原116/104补修，保留[复核说明](final-review-interpretation.json)。
- 资源预约提前7天边界、系统用户合法状态FROZEN、公告publishTime必填和浏览器隐藏checkbox/重复文本定位属于测试数据/脚本修正；没有当作产品修复。
- 上传type按决策日志2026-09-09裁决可省略并默认IMAGE；未擅改冻结30。通知补拉参数为sinceSeq，原脚本lastSeq笔误已用非零游标真实复核。
- `performance-harness-first.json`为局部变量遮蔽内置函数造成的启动即中断，不采用为正式性能轮次；修正后基础/N7两轮完整重新采样。脚本问题与产品故障分别留痕。驳回补验脚本初次种子房号超过字段长度，缩短后才进入真实并发，未把建数失败计为产品故障。
- 故障窗口启动/重连可能出现额外即时请求，周期判据要求至少7个约30秒周期；所有时间间隔仍保留，未把额外请求冒作周期轮询。

## 4. 结论范围

金融沿用户本人确认及既有工程证据，不新增真实渠道异常通过结果。305 TC不是本轮305次完整执行；18项逐条采用范围明确，不把Mock分支断言称作真实数据库竞争。原03绝对性能门槛保持，N7相对变化与故障窗口的慢响应单独记录。专用进程已回收、独立合成库和本机原始数据保留；正式评审及阶段签署由用户完成。
