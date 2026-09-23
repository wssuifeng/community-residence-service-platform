# 二轮复跑逐步骤结果（自动汇总）

| 流程 | 步骤 | 判定 | 截图 |
|---|---|---|---|
| CF-E4 工单完整链路 | 1 居民提交工单（类别 室内维修 + 1 张图片）→ PENDING 待受理；**受理前无群**（断言①） | PASS | *E4_1_submitted.png |
| CF-E4 工单完整链路 | 2b 管理端「受理」→ TO_ASSIGN 待派单 + 同事务自动建群（回带 conversationId） | PASS | 001_E4_2b_intake_done_list.png |
| CF-E4 工单完整链路 | 2a 「受理」入口前置：仅 PENDING 行可见 | PASS | 001_E4_2b_intake_done_list.png |
| CF-E4 工单完整链路 | 2c 受理后居民端出现群聊面板；群成员=提交人+受理管理员（断言②） | PASS | 001_E4_2c_resident_group_panel.png |
| CF-E4 工单完整链路 | 3a 管理端群内发言 → 居民端免刷新即时可见（WS ≤3s） | PASS | 001_E4_3b_resident_realtime.png |
| CF-E4 工单完整链路 | 3b 派单前 staff1 不在群（断言④前半：非参与者不可见/403） | PASS | 001_E4_3c_staff_not_in_group.png |
| CF-E4 工单完整链路 | 4 管理端派单给 staff1 → ASSIGNED 已派单 + **staff1 自动入群（三方齐）**（断言③） | PASS | 001_E4_4_members_three.png |
| CF-E4 工单完整链路 | 5a 服务人员端「待接单」出现该单并可进入详情（已入群） | PASS | 002_E4_5a_staff_todo.png |
| CF-E4 工单完整链路 | 5b 服务人员「接单」→ ACCEPTED 已接单；群内发言三方互通（断言④后半） | PASS | 003_E4_5b_accepted_group_msg.png |
| CF-E4 工单完整链路 | 6 服务人员「开始处理」→ IN_PROGRESS 处理中 | PASS | 004_E4_6_in_progress.png |
| CF-E4 工单完整链路 | 7 服务人员「提交处理结果」（＝服务人员确认）+ 1 张图片 → TO_CONFIRM 待确认 | FAIL | 005_E4_7_to_confirm.png |
| CF-E4 工单完整链路 | 8 居民「确认完成」（二次确认）→ COMPLETED 已完成 + 出现「服务评价」区 | PASS | 006_E4_8_completed_with_eval.png |
| CF-E4 工单完整链路 | 9 终审结束入口前置：仅 COMPLETED 行可见，TO_CONFIRM 行不可见（断言⑦前半） | PASS | 007_E4_9_finalize_gate.png |
| CF-E4 工单完整链路 | 10 管理端「终审结束」（二次确认含会话回收说明）→ CLOSED 已关闭（断言⑧） | PASS | 009_E4_10b_closed.png |
| CF-E4 工单完整链路 | 11 会话回收后：历史可读 + 面板只读 + **再发言被拒 5604**（断言⑧后半） | PASS | 010_E4_11_recycled_readonly.png |
| CF-E4 工单完整链路 | 13a 旁支：管理端对 PENDING 单「驳回」（理由必填校验生效）→ REJECTED | PASS | 011_E4_13a_rejected.png |
| CF-E4 工单完整链路 | 13b 旁支：居民端「取消工单」→ CANCELLED（未受理单不建群） | PASS | 012_E4_13b_cancelled.png |
| CF-E4 工单完整链路 | 14 旁支：居民对 TO_CONFIRM 单「不满意退回」→ IN_PROGRESS 处理中 | PASS | 013_E4_14_returned.png |
| CF-E4 工单完整链路 | 12 通知矩阵核对（提交→admin1 + superadmin；居民口径不串号）（断言⑨） | PASS | 018_E4_12_matrix.png |
| CF-E4-X1 跨身份空间越权（DEF-075 回归） | 1 撞击号验证：superadmin（sys_user.id=1）访问只含 resident1（resident.id=1）参与的工单群 → 应 403 | PASS | 001_X1_superadmin_403.png |
| CF-E4-X1 跨身份空间越权（DEF-075 回归） | 2 对照（真实参与者）：resident1 → 200（证明不是一律拒绝） | PASS | 002_X1_resident1_200.png |
| CF-E4-X1 跨身份空间越权（DEF-075 回归） | 3 对照（管理员参与者）：admin1 → 200；非参与者 resident2 → 403（同口径扩大到居民身份空间） | PASS | — |
| CF-E4-X1 跨身份空间越权（DEF-075 回归） | 4 前端表现：superadmin 直接访问该会话详情 URL 不渲染会话内容 | PASS | — |
| CF-E4-X1 跨身份空间越权（DEF-075 回归） | 5 前端：superadmin 打开 /admin/conversations/9 | PASS | 003_X1_superadmin_conversation_page.png |
| CF-E3-F 押金退还 | 0 起手数据核对：租约 4 = MOVED_OUT + 押金已缴 + 历史成功首期单（7600 = 3800 租金 + 3800 押金） | PASS | 001_E3F_0_start.png |
| CF-E3-F 押金退还 | 4-neg-5704 金额超实缴（租约 4 已缴，金额 999999） | FAIL | — |
| CF-E3-F 押金退还 | 4-neg-5705 渠道与实缴不一致（租约 4 实缴支付宝，传微信） | FAIL | — |
| CF-E3-F 押金退还 | 4-neg-5703 押金未缴（租约 7 = MOVED_OUT + UNPAID） | FAIL | — |
| CF-E3-F 押金退还 | 4-neg-5602 非已搬出租约（租约 5 ACTIVE / 租约 6 ACTIVE） | FAIL | — |
| CF-E3-F 押金退还 | 4a 租约详情「发起押金退还」入口可用（MOVED_OUT + PAID） | PASS | 002_E3F_4a_before_refund.png |
| CF-E3-F 押金退还 | 4b 弹窗：押金状态 / 实缴押金 / 可退金额 / **渠道只读＝原渠道** / 金额默认全额 | PASS | 003_E3F_4b_dialog.png |
| CF-E3-F 押金退还 | 4c 确认发起退还 → 退还记录 PENDING → 渠道结果；押金状态 PAID→REFUNDING→(SUCCESS)REFUNDED 或 (失败)回落 PAID | FAIL | 004_E3F_4c_refund_result.png |
| CF-E3-F 押金退还 | 5 居民端：押金徽标（五态）+ 押金退还流水（金额/渠道/状态/失败原因/时间/渠道退款单号） | PASS | 006_E3F_5_resident_deposit.png |
| CF-E3-F 押金退还 | 5b 越权：resident2（非本人租约）读取租约 4 押金退还流水 → 应被拒（仅本人可见） | PASS | — |
| CF-E3-F 押金退还 | 6 租约明细时间线：状态流转 + 支付流水 + 押金退还**三源合并**、时间倒序；支付条目含金额拆分 / 覆盖租期 / 业务单号 / 渠道 | PASS | 007_E3F_6_timeline.png |
| CF-E3-F 押金退还 | 7 管理端对已搬出租约「归档」→ ARCHIVED（终态，不可修改）；历史可查 | PASS | 008_E3F_7_archived.png |
| CF-E3-F 押金退还（修复后构建） | 4-neg-5704 金额超实缴（租约 4 已缴，金额 999999）→ 拒 5704（报文含可退金额） | PASS | — |
| CF-E3-F 押金退还（修复后构建） | 4-neg-5705 渠道与实缴不一致（实缴支付宝，传微信）→ 拒 5705 | PASS | — |
| CF-E3-F 押金退还（修复后构建） | 4-neg-5706 同 outRequestNo 但金额不一致 → 拒 5706（幂等键冲突） | PASS | — |
| CF-E3-F 押金退还（修复后构建） | 4-neg-5602 非已搬出租约（admin2 对租约 5 ACTIVE）→ 拒 5602 | PASS | — |
| CF-E3-F 押金退还（修复后构建） | 1 居民端（resident2 · 租约 2）「申请退租」→ 状态「待审核」 | PASS | 001_E3F2_1_moveout_dialog.png |
| CF-E3-F 押金退还（修复后构建） | 1b 同一租约重复发起退租 → 拒 5603（MOVE_OUT_APPLICATION_PENDING） | FAIL | — |
| CF-E3-F 押金退还（修复后构建） | 2a 管理端「租住与房源 → 退租申请」出现待审核申请 | FAIL | 002_E3F2_2_admin_moveout_pending.png |
| CF-E3-F 押金退还（修复后构建） | 4-neg-5703 押金未缴（租约 2 = MOVED_OUT + UNPAID）→ 拒 5703 | FAIL | — |
| CF-E3-F 押金退还（修复后构建） | 3 驳回分支：管理端驳回退租申请（理由必填）→ 租约保持已生效 | PASS | 003_E3F2_3_rejected.png |
| CF-E3-F 押金退还（修复后构建） | 4a 租约 4（MOVED_OUT + PAID）「发起押金退还」入口可用 | PASS | 004_E3F2_4a_entry.png |
| CF-E3-F 押金退还（修复后构建） | 4b 弹窗：押金状态/实缴/可退金额/渠道只读＝原渠道/金额默认全额/幂等请求号 | PASS | 005_E3F2_4b_dialog.png |
| CF-E3-F 押金退还（修复后构建） | 4c 确认发起退还 → PENDING → 渠道（沙箱）失败 → 失败回落 PAID + 失败原因常驻 | PASS | 006_E3F2_4c_result.png |
| CF-E3-F 押金退还（修复后构建） | 4d 失败重试（换新 outRequestNo）→ 新增一条记录（不复用旧号） | PASS | 007_E3F2_4d_retry.png |
| CF-E3-F 押金退还（修复后构建） | 5 居民端：押金徽标 + 押金退还流水（金额/渠道/状态/失败原因/时间/渠道退款单号） | PASS | 008_E3F2_5_resident_refund_records.png |
| CF-E3-F 押金退还（修复后构建） | 6 租约明细时间线：状态流转 + 支付流水 + 押金退还三源合并、时间倒序；支付条目含金额拆分/覆盖租期/业务单号/渠道 | PASS | 009_E3F2_6_timeline.png |
| CF-E3-F 退租审核联动 + 5703（superadmin 全局限） | 2a 管理端「租住与房源 → 退租申请」出现 resident2 的待审核申请（超管全局视图） | PASS | 001_E3F4_1_admin_moveout_pending.png |
| CF-E3-F 退租审核联动 + 5703（superadmin 全局限） | 2b 审核通过退租 → 租约「已搬出」+ 押金仍「未缴」（退还需单独发起）+ 房源按选择回收 | PASS | 002_E3F4_2_approve_dialog.png |
| CF-E3-F 退租审核联动 + 5703（superadmin 全局限） | 2c 联动：居住关系终止（记搬出时间）+ 租约群聊关闭（如有） | SKIP | 003_E3F4_2c_relation.png |
| CF-E3-F 退租审核联动 + 5703（superadmin 全局限） | 4-neg-5703 押金未缴（租约 2 = MOVED_OUT + UNPAID）→ 拒 5703 | PASS | — |
| CF-E3-F 退租审核联动 + 5703（superadmin 全局限） | obs 对 GET-only 集合端点用 POST → 应为 405 Method Not Allowed，实际 500 系统内部错误（低危） | INFO | — |
| CF-E3-F 退租联动一致性（2c 复核） | 2c 退租通过后联动：居住关系按搬出日期终止（不再有效） | INFO | 001_E3F4_2c_relation.png |
