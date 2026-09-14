# 后端主会话交接提示词（frontend-beautify → 主工作区）

> 用法：在主工作区（main 分支，50 系统测试阶段）新开一个会话，把下面分隔线内的全文作为首条消息粘贴。

---

我已在新会话启动。请先按 AGENTS.md 纪律读取 `docs/00_总控.md` 与 `docs/50_系统测试/_中控.md` 了解当前阶段状态，然后处理以下前端美化工作区（frontend-beautify 分支，已推送 origin）移交的后端适配事项。背景材料：仓库根目录的 `HANDOVER.md`（交接文档）与 `BEAUTIFY_NOTES.md`（状态唯一源，「后端适配清单」一节为全部明细，含每条的前端处理方式与建议方案）。前端零接口破坏：所有适配是「后端补齐/修正文档」方向，不需要前端配合改动的项已由前端自行兜底并标注。

## 一、必须裁决的契约差异（前端已按后端真实行为对齐，需反向修订文档或补端点）

1. **接口设计.md 三处修订**（文档与实现不符，以代码为准）：
   - 9.7.1.1 创建预约请求体：文档写 timeslotId/participants，后端 CreateReservationDTO 实为 resourceId/reserveDate/startTime/endTime/purpose/contactPhone/remark（前端旧表单按文档写曾必然 400，已按实际对齐）
   - 9.2.3.2 房屋住户列表：字段实为 residentPhone（文档写 phone）
   - 9.8 评价跟进：跟进实为单 content 字段（文档三字段），followups 实为纯数组非分页
2. **公告管理（D2 裁决）**：管理端表单照常提交 priority/type/expireTime，后端保存接口目前不接收、NoticeVO 未暴露 is_pinned（notice 表有列但代码零引用）——需裁决：补齐接收+VO 暴露，或走接口设计变更流程删字段

## 二、建议补的端点/约束（前端已用兜底方案，不阻塞）

3. **楼栋事务级联删除**：`DELETE /buildings/{id}`（或 cascade 参数）——参照 stage-50-1b 社区级联删除事务模式；现状前端自底向上编排删除（房屋→单元→楼栋），中途失败留部分删除状态并明示用户
4. **批量创建端点**：楼栋/单元/房屋批量（整栋创建上限场景前端循环约 310 请求）；或提供事务性批量接口
5. **管理员直建居住关系端点**：现状仅居民申请审批流，管理端住户抽屉的「登记住户」降级为跳审批页
6. **预约表唯一约束**：resource_reservation 无 (user_id, resource_id, reserve_date, start_time) 唯一索引，应用层 5002 查重仅顺序请求有效，并发可穿透
7. **available-slots 口径**：currentBookings 仅计与模板起止完全相等的预约（子区间漏计）；同日时段无稳定排序（前端已按 startTime 兜底）；日容量合计（ΣmaxBookings）与资源 capacity 两套口径并存——建议决策日志裁决

## 三、预存在缺陷（前端美化时发现并核实，归 50 阶段缺陷跟踪）

8. ADMIN 调 `GET /sys-users` 403 → 社区管理员无法派单（原前端注释假设与后端不符）
9. 工单号 4 位随机号撞唯一键直接 409，注释声称的重试未实现
10. `GET /feedbacks` 无 keyword 参数；反馈 close() 不推 WS（对端滞后一个轮询周期）
11. unsatisfied 评价无 hasFollowup 参数（前端 N+1 工作台方案在用）
12. 统计接口无 STAFF 可访问的按日序列（服务人员端趋势为 MOCK 标注）；工单列表无时间范围过滤与多状态分页（BEAUTIFY_NOTES 待补接口 ①~⑧ 全清单沿用）
13. 创建用户表单「手机号选填」vs 后端 @NotBlank 契约漂移；admin1 操作日志为空属 DataScope 按 community_id 过滤现状（需确认是否有意）
14. 公告 `GET /notices` 缺 priority 过滤参数；审批通过响应实为申请 VO（文档组装结果漂移）

## 四、合并与数据事项

- 前端分支 `frontend-beautify` 已推送（5 个分域提交：文档+设计稿 / 共享基建 / 管理端 / 其余三端+auth / 补漏），本地资产（截图/验证脚本）未入库；合并方式见 HANDOVER.md §三
- dev 库残留待裁决：R3 预约 2 条 PENDING（id 28/29）、A6 终态预约 19 条+违约 4 条、unit 14「二单元」、各任务验收数据（BEAUTIFY_NOTES「验收数据残留汇总」逐条列明清理方式）；如需零残留须 SQL 清理或重建 V6 种子库
- 前端 52+17 条遗留 Minor 清单在 BEAUTIFY_NOTES「管理端遗留 Minor 清单」，归用户逐条裁定，不阻塞合并

请先通读 HANDOVER.md 与 BEAUTIFY_NOTES 的「后端适配清单」节，然后给我一个分类处置建议（哪些归 50 阶段缺陷跟踪、哪些走决策日志裁决、哪些建议直接实现），经我确认后再动手。

---
