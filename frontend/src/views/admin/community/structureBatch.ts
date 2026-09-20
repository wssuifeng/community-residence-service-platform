/**
 * 房号生成规则与「跳过项」语法（社区管理批量建房共用模块，2026-09-21）。
 *
 * 单一实现来源：结构生成弹窗（StructureGenerateDialog，一次建 楼栋→单元→房屋）与
 * 单元精确建房弹窗（HouseBatchDialog，单元内补建房屋）共用本模块，
 * 保证同一套跳过项写法在两处的解析、预览与统计口径完全一致（避免两份实现漂移）。
 *
 * 术语：房号（门牌号）= 前缀 + 楼层 + 补零序号 + 后缀；跳过项语法见 SKIP_SYNTAX_HINT。
 */

/** 房号序号补零宽度缺省值（楼层 + 两位序号 → 101 / 104，与结构总览既有口径一致） */
export const HOUSE_NUMBER_WIDTH = 2

/**
 * 跳过项语法（界面原样展示，避免实现与提示漂移）：
 *  4      → 第 4 层整层
 *  4:1    → 第 4 层 1 号（精确到单套）
 *  04     → 所有楼层的 4 号（两位补零即序号；序号 ≥ 10 写作 *:12）
 *  *:4    → 所有楼层的 4 号（通配楼层写法，等价于 04）
 *  104    → 基础门牌号（1 层 4 号，等价于 1:4）
 *  A-101  → 含前后缀的完整门牌号
 *  分隔符：逗号 / 顿号 / 空格；无法识别的片段原样提示忽略，不阻断生成。
 */
export const SKIP_SYNTAX_HINT =
  '4 整层 ｜ 4:1 第4层1号 ｜ 04 所有层4号 ｜ 104 基础房号 ｜ A-101 完整房号；逗号/顿号/空格分隔'

/** 生成网格中的一行（floor/seq 参与跳过匹配，base 与 houseNumber 参与房号匹配） */
export interface GenerateRow {
  floor: number
  /** 该层房号序号（跳过项按序号跨层匹配时使用） */
  seq: number
  /** 基础门牌号（楼层 + 补零序号，不含前后缀） */
  base: string
  /** 最终门牌号（拼接前后缀后提交后端） */
  houseNumber: string
}

/** 跳过项种类：整层 / 跨层序号 / 指定层房号 / 基础门牌号 / 完整门牌号 */
export type SkipRuleKind = 'floor' | 'seq' | 'spot' | 'base' | 'number'

export interface SkipRule {
  /** 用户原始输入片段（未命中与非法提示回显用） */
  raw: string
  kind: SkipRuleKind
  floor?: number
  seq?: number
  /** base / number 的比对文本（大写） */
  text?: string
}

export interface ParsedSkipRules {
  rules: SkipRule[]
  /** 无法识别的片段（原样提示，不阻断生成） */
  invalid: string[]
}

const SPOT_PATTERN = /^(\*|\d{1,2})\s*[:：]\s*(\d{1,2})$/
/** 两位补零（01~09）即序号；一位数或无前导零的两位数是楼层 */
const PADDED_SEQ_PATTERN = /^0\d$/

/** 输入解析：中英文逗号、顿号、空格（含连续）皆作分隔；空片段忽略 */
export function parseTokens(raw: string): string[] {
  return raw
    .split(/[,，、\s]+/)
    .map((token) => token.trim())
    .filter(Boolean)
}

/* 跳过项分类（位数即语义）：1~2 位纯数字＝整层楼层；两位补零或 *:n＝跨层序号；
   3~4 位＝基础门牌号（门牌号 = 楼层 + 补零序号）；含非数字字符＝完整门牌号（前后缀场景） */
export function parseSkipRules(raw: string): ParsedSkipRules {
  const rules: SkipRule[] = []
  const invalid: string[] = []
  for (const token of parseTokens(raw)) {
    const spot = SPOT_PATTERN.exec(token)
    if (spot) {
      const seq = Number(spot[2])
      if (seq > 0) {
        const floor = spot[1] === '*' ? undefined : Number(spot[1])
        rules.push(
          floor === undefined
            ? { raw: token, kind: 'seq', seq }
            : { raw: token, kind: 'spot', floor, seq }
        )
      } else {
        invalid.push(token)
      }
      continue
    }
    if (/^\d+$/.test(token)) {
      const value = Number(token)
      if (value <= 0 || token.length > 4) {
        invalid.push(token)
      } else if (PADDED_SEQ_PATTERN.test(token)) {
        rules.push({ raw: token, kind: 'seq', seq: value })
      } else if (token.length <= 2) {
        rules.push({ raw: token, kind: 'floor', floor: value })
      } else {
        rules.push({ raw: token, kind: 'base', text: token.toUpperCase() })
      }
      continue
    }
    rules.push({ raw: token, kind: 'number', text: token.toUpperCase() })
  }
  return { rules, invalid }
}

/** 门牌号构成：楼层 + 补零序号（width 默认 2：floor 2 × 序号 3 → 203） */
export function baseHouseNumberOf(
  floor: number,
  seq: number,
  width: number = HOUSE_NUMBER_WIDTH
): string {
  return `${floor}${String(seq).padStart(width, '0')}`
}

export function skipRuleMatches(rule: SkipRule, row: GenerateRow): boolean {
  if (rule.kind === 'floor') return row.floor === rule.floor
  if (rule.kind === 'seq') return row.seq === rule.seq
  if (rule.kind === 'spot') return row.floor === rule.floor && row.seq === rule.seq
  if (rule.kind === 'base') return row.base.toUpperCase() === rule.text
  return row.houseNumber.toUpperCase() === rule.text
}

/** 命中该行的首个跳过项（预览标注跳过原因用），未命中返回 null */
export function firstMatchSkipRule(
  rules: SkipRule[],
  row: GenerateRow
): SkipRule | null {
  return rules.find((rule) => skipRuleMatches(rule, row)) ?? null
}

/** 跳过项语义回显（预览原因与统计文案共用） */
export function skipRuleLabel(rule: SkipRule): string {
  if (rule.kind === 'floor') return `${rule.floor} 层整层`
  if (rule.kind === 'seq') return `每层 ${String(rule.seq).padStart(2, '0')} 号`
  if (rule.kind === 'spot') return `${rule.floor} 层 ${rule.seq} 号`
  return `房号 ${rule.text}`
}

/** 被跳过的一行及其原因（规则跳过 / 撞号去重，手动作废由调用方追加） */
export interface SkippedRow {
  houseNumber: string
  reason: string
}

export interface HousePlanInput {
  floorStart: number
  floorEnd: number
  seqStart: number
  seqEnd: number
  prefix?: string
  suffix?: string
  width?: number
  skip?: string
}

export interface HousePlan {
  /** 全量网格（未做任何排除），统计被规则排除的套数用 */
  all: GenerateRow[]
  /** 规则跳过后保留且已按房号去重的行（提交清单） */
  kept: GenerateRow[]
  /** 撞号被剔除的行（门牌号与已保留行重复，提交必被后端同单元唯一约束拒绝） */
  deduped: GenerateRow[]
  /** 规则跳过的行（含原因，预览灰字删除线展示） */
  skipped: SkippedRow[]
  /** 规则跳过拆分：整层 / 指定房号（统计文案用） */
  skippedByFloor: number
  skippedByHouse: number
  invalidTokens: string[]
  /** 未命中当前生成范围的规则片段：写法或范围有误时提示，避免用户以为已生效 */
  unmatchedTokens: string[]
}

/* 规则收敛：一次遍历同时产出保留行与被去重剔除的行（预览与提交共用同一清单）。
   门牌号由楼层 + 序号拼成，跨楼层理论上可能撞号，按首次出现保留 */
export function planHouseRows(input: HousePlanInput): HousePlan {
  const { floorStart, floorEnd, seqStart, seqEnd } = input
  const prefix = input.prefix ?? ''
  const suffix = input.suffix ?? ''
  const width = input.width ?? HOUSE_NUMBER_WIDTH
  const parsed = parseSkipRules(input.skip ?? '')

  const all: GenerateRow[] = []
  for (let floor = floorStart; floor <= floorEnd; floor += 1) {
    for (let seq = seqStart; seq <= seqEnd; seq += 1) {
      const base = baseHouseNumberOf(floor, seq, width)
      all.push({ floor, seq, base, houseNumber: `${prefix}${base}${suffix}` })
    }
  }

  const seen = new Set<string>()
  const kept: GenerateRow[] = []
  const deduped: GenerateRow[] = []
  const skipped: SkippedRow[] = []
  let skippedByFloor = 0
  let skippedByHouse = 0

  for (const row of all) {
    const rule = firstMatchSkipRule(parsed.rules, row)
    if (rule) {
      skipped.push({ houseNumber: row.houseNumber, reason: `跳过（${skipRuleLabel(rule)}）` })
      if (rule.kind === 'floor') {
        skippedByFloor += 1
      } else {
        skippedByHouse += 1
      }
      continue
    }
    if (seen.has(row.houseNumber)) {
      deduped.push(row)
      continue
    }
    seen.add(row.houseNumber)
    kept.push(row)
  }

  const unmatchedTokens = parsed.rules
    .filter((rule) => !all.some((row) => skipRuleMatches(rule, row)))
    .map((rule) => rule.raw)

  return {
    all,
    kept,
    deduped,
    skipped,
    skippedByFloor,
    skippedByHouse,
    invalidTokens: parsed.invalid,
    unmatchedTokens
  }
}
