# 批次C工单与评价需求验收记录

日期：2026-10-19。任务：60-02.1批次C。范围：R17～R23、R37～R39、R65、R74，以及E4、E8。金融部分不在本批范围，沿用户确认采用既有可用结论，不重复支付、查单、退款或渠道配置测试。

本记录只判断需求是否按当前20包需求及已批准修订实现，不开展11项质量评估。对50阶段证据的采用条件是：需求口径、源码版本、角色/数据范围和证据内容均适用；“有实现或局部单测”不能替代完整流程验收。

## 1. 版本、范围与证据

- 执行开始时文档提交为 `a569bf37076f39e848f05bdfa14ceed9bcd149c1`；产品代码基线为 `95b2fe44954167de547d8b10f2cda869720c10c6`。从产品基线到当前提交，后端与前端源代码无差异，因此适用的50阶段证据可复用。
- 当前只读运行入口：后端 `http://localhost:8084`、前端 `http://localhost:5176`；OpenAPI 3.1、211条路径。`/v3/api-docs`、前端根入口和 `/resident` 返回200；业务写入0、金融操作0。探针见 [runtime-readonly.json](evidence/acceptance-c-20261019/runtime-readonly.json)。
- 证据总入口：[source-review.json](evidence/acceptance-c-20261019/source-review.json)、[50阶段结构化RTM](../50_系统测试/evidence/db-final-reconciliation-20261019/rtm-ledger.json)、[50阶段用例台账](../50_系统测试/evidence/db-final-reconciliation-20261019/case-ledger.json)、[最终后端定向回归](../50_系统测试/evidence/df-final-remediation-20261019/backend-targeted.json)。
- 工单群聊专项可核对 [ConversationServiceWorkOrderGroupTest.java](../../backend/src/test/java/com/community/residence/common/conversation/service/ConversationServiceWorkOrderGroupTest.java)、[WorkOrderR74IntakeTest.java](../../backend/src/test/java/com/community/residence/common/workorder/service/WorkOrderR74IntakeTest.java) 和 [workorder-group-chat.spec.ts](../../frontend/src/__tests__/workorder-group-chat.spec.ts)。R74现行后端定向套件包含7项 `WorkOrderR74IntakeTest`，全部通过。
- R65的实现/局部单测入口为 [StaffCapabilityServiceTest.java](../../backend/src/test/java/com/community/residence/common/workorder/service/StaffCapabilityServiceTest.java)、[StaffScheduleServiceTest.java](../../backend/src/test/java/com/community/residence/common/workorder/service/StaffScheduleServiceTest.java) 和 [WorkOrderDispatchTest.java](../../backend/src/test/java/com/community/residence/common/workorder/service/WorkOrderDispatchTest.java)；但50阶段RTM已明确“无独立完整排班TC，不伪称候选一例即全覆盖”，故本批不将其直接升格为R65通过。

## 2. 功能需求逐条结论

| 对象 | 当前判定 | 依据与实际核对 | 差距/限制 |
|---|---|---|---|
| R17 | 通过 | 服务类别CRUD、停用过滤和删除保护有A5历史证据；停用类别过滤及现行入口口径由现行定向证据复核。 | 未向正常库新建或停用类别。 |
| R18 | 通过 | 居民提交工单、工单号、参数校验、多图附件和角色拒绝有A5/B3及DEF-026回归证据。 | 未向正常库提交工单或上传附件。 |
| R19 | 通过 | 驳回理由、受理前取消和状态分支有A5/BJ/CF/BF证据；现行口径按受理独立动作、受理建群及状态机核对。 | 未在正常库执行受理、驳回或取消写入。 |
| R20 | 通过 | 派单对象角色/启用校验、被派人员接单与处理权限、候选入口有A5/BJ/CF/BF及派单链证据。 | R65的候选排序专项另按证据不足处理，不反向否定R20基础派单与接单判据。 |
| R21 | 通过 | 处理过程、结果文字必填、多图可选及居民可见有A5和现行服务侧证据。 | 未在正常库提交处理结果。 |
| R22 | 通过 | 居民确认、满意/不满意退回、管理员终审关闭、关闭留痕和现行状态机有A5/BJ/CF/BF证据；当前源码已具备不满意退回处理入口。 | 未在正常库推进工单状态。 |
| R23 | 通过 | 状态流转时间线、操作人/时间/备注、三角色可见及越权拒绝有A5/B3/D5/WS1/BJ/CF/BF证据。 | 未新增流程记录。 |
| R37 | 通过 | 已完成/关闭工单评价边界、一单一评、星级边界、提交人权限和读取权限有A5/BF/BI及评价读取专项证据。 | 未向正常库新增评价。 |
| R38 | 通过 | ≤2星进入不满意跟进、未跟进不可关闭、已跟进可关闭、满意评价不进入跟进，以及`hasFollowup`过滤有A5/B3/D5/WS1证据。 | 未向正常库新增跟进记录。 |
| R39 | 通过 | 按服务人员、服务类别双维度聚合及SQL/接口一致性有A5和评价聚合修复证据。 | 本批采用既有核对结果，不重复造数。 |
| R65 | 证据不足 | 当前代码和局部单测覆盖全量覆盖式能力绑定、排班覆盖/清空、五班次、调度标记、等待时长、班次/负载回填和四档候选排序；但50阶段RTM明确没有独立完整R65用例执行，最终证据未把这些判据放入同一当前版本组合场景核对。 | G-010。需补一份隔离环境的R65组合证据，或由用户审阅后接受该证据边界；不把局部单测数量当作需求通过。 |
| R74 | 通过 | 受理与建群同事务、居民/管理员入群、派单后服务人员幂等入群、参与者隔离、终审关闭、`CLOSED + closed_at`、历史可读、关闭后发言5604及不物理删除均有会话侧和工单侧专项证据；当前最终定向回归中的R74服务套件7/7通过。 | 未在正常库创建或关闭新工单群。 |

## 3. 流程对象结论

| 流程 | 当前判定 | 已核对内容 | 限制 |
|---|---|---|---|
| E4 工单、评价与群聊关闭 | 证据不足 | E4各功能节点、通知深链、状态链和R74群生命周期均有历史/现行定向证据；TC-C4-020已有现行定向映射。 | 当前版本没有一次完整的提交→受理建群→派单入群→接单处理→结果→居民确认→评价→终审关闭→会话回收跨端记录。G-011。 |
| E8 评价及不满意跟进 | 证据不足 | 评价首次提交、重复拒绝、不满意跟进、聚合统计及过滤均有A5/B3/D5/WS1和评价专项证据。 | 没有当前版本同一批业务数据完成“完成→评价→≤2星跟进→记录→计入统计”的完整链路证据。G-011。 |

## 4. 批次结论与后续去向

本批完成12条功能需求和2个流程对象的核对：R17～R23、R37～R39、R74共11条形成通过结论；R65因组合证据不足暂不通过；E4、E8均为证据不足。没有在本批新增已确认的功能实现缺陷。

新增差距：G-010（R65组合证据不足）和G-011（E4/E8当前完整流程证据不足）。两项均是需求验收证据缺口，不转化为质量评估结论，也不启动60-03整改。继续执行后续功能需求批次；全部需求验收完成后再汇总60-03整改/补证与复验。金融仍沿用户确认，不新增交易操作。
