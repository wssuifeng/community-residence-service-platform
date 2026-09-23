/**
 * 后端业务错误码契约（唯一来源：backend common/constant/ErrorCode.java，
 * 接口口径以 30_系统设计/接口设计.md 为准）。
 * 前端只复刻需要按 code 分支处理的错误码；视图内禁止散落错误码魔法数字，
 * 后端错误码调整时本文件同步更新。
 */

/** ErrorCode.APPLICATION_PENDING_EXISTS(5206)：同房屋已有待审核的入住申请（重复提交被拒时按此分支引导） */
export const APPLICATION_PENDING_EXISTS = 5206
