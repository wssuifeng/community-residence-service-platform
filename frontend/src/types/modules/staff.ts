/**
 * C4 服务人员能力与排班类型定义（V19：服务人员-常驻社区绑定、擅长服务类别、排班）
 * 对齐后端 StaffCapabilityController / StaffScheduleController 实测契约。
 * 服务人员 = sys_user 中 role='STAFF' 的账号，无独立 staff 表。
 */
import type { PageQuery } from '@/types/api'

/* ---------------------------------- 班次 ---------------------------------- */

/** 班次类型（后端 ShiftType：MORNING-早班, AFTERNOON-午班, EVENING-晚班, FULL-全天, REST-休息） */
export type ShiftType = 'MORNING' | 'AFTERNOON' | 'EVENING' | 'FULL' | 'REST'

/** 班次中文标签 */
export const shiftTypeLabels: Record<ShiftType, string> = {
  MORNING: '早班',
  AFTERNOON: '午班',
  EVENING: '晚班',
  FULL: '全天',
  REST: '休息'
}

/** 班次默认时间区间（REST 无时间；与后端 ShiftType 默认值一致，仅用于前端表单回填） */
export const shiftTypeDefaults: Record<ShiftType, { startTime: string | null; endTime: string | null }> = {
  MORNING: { startTime: '08:00', endTime: '12:00' },
  AFTERNOON: { startTime: '12:00', endTime: '18:00' },
  EVENING: { startTime: '18:00', endTime: '22:00' },
  FULL: { startTime: '08:00', endTime: '18:00' },
  REST: { startTime: null, endTime: null }
}

/** 排班可选班次顺序（供表单枚举） */
export const SHIFT_TYPE_OPTIONS: ShiftType[] = ['MORNING', 'AFTERNOON', 'EVENING', 'FULL', 'REST']

/** 班次标签配色（按班次类型着色，排班表单元格用） */
export const shiftTypeColors: Record<ShiftType, { bg: string; fg: string }> = {
  MORNING: { bg: 'rgba(56, 189, 248, 0.16)', fg: '#0369a1' },
  AFTERNOON: { bg: 'rgba(251, 191, 36, 0.18)', fg: '#b45309' },
  EVENING: { bg: 'rgba(129, 140, 248, 0.18)', fg: '#4338ca' },
  FULL: { bg: 'rgba(52, 211, 153, 0.18)', fg: '#047857' },
  REST: { bg: 'rgba(148, 163, 184, 0.16)', fg: '#64748b' }
}

/* ------------------------------- 服务人员能力 ------------------------------- */

/** 服务人员能力档案（常驻社区 + 擅长服务类别） */
export interface IStaffCapability {
  staffId: number
  username: string
  realName: string
  phone?: string
  /** 账号状态：可见值以 sys_user.status 为准 */
  status?: string
  /** 常驻社区ID列表 */
  communityIds: number[]
  /** 常驻社区名称列表（与 communityIds 同序） */
  communityNames: string[]
  /** 擅长服务类别ID列表 */
  categoryIds: number[]
  /** 擅长服务类别名称列表（与 categoryIds 同序） */
  categoryNames: string[]
}

/** 能力绑定保存请求（PUT /staff-capabilities/{staffId}，全量覆盖式：空数组=清空该维度） */
export interface ISaveStaffCapabilityDTO {
  communityIds?: number[]
  categoryIds?: number[]
}

/** 能力绑定列表查询参数 */
export interface IStaffCapabilityQuery extends PageQuery {
  communityId?: number
  categoryId?: number
  /** 匹配 realName / username / phone */
  keyword?: string
}

/** 可绑定人员候选项（GET /staff-capabilities/candidates） */
export interface IStaffCandidate {
  id: number
  realName: string
  username: string
}

/* ---------------------------------- 排班 ---------------------------------- */

/** 排班记录（GET /staff-schedules；一人一社区一天一条） */
export interface IStaffSchedule {
  id: number
  staffId: number
  staffName: string
  communityId: number
  communityName: string
  /** 排班日期（YYYY-MM-DD） */
  workDate: string
  shiftType: ShiftType
  /** 班次中文标签（后端回填） */
  shiftLabel: string
  /** 班次开始时间（HH:mm:ss；REST 为空） */
  startTime?: string | null
  endTime?: string | null
  remark?: string
}

/** 排班查询参数（日期范围必填，跨度上限 62 天） */
export interface IStaffScheduleQuery {
  communityId?: number
  /** yyyy-MM-dd */
  startDate: string
  /** yyyy-MM-dd */
  endDate: string
  staffId?: number
}

/** 排班批量设置请求（PUT /staff-schedules/batch；同 staff+社区+日期已存在则覆盖） */
export interface IBatchSaveScheduleDTO {
  communityId: number
  staffIds: number[]
  /** yyyy-MM-dd 列表 */
  dates: string[]
  shiftType: ShiftType
  /** 自定义起止时间（HH:mm）；不传则用班次默认值，REST 强制清空 */
  startTime?: string
  endTime?: string
  remark?: string
}

/** 排班写操作结果（后端 BatchSaveScheduleResultVO：受影响条数） */
export interface IBatchSaveScheduleResult {
  saved: number
}

/** 排班范围清空参数（DELETE /staff-schedules） */
export interface IClearScheduleParams {
  communityId: number
  /** 为空表示该社区全部人员 */
  staffIds?: number[]
  startDate: string
  endDate: string
}
