/**
 * 日期格式化工具（后端 ISO 8601 → 界面展示）
 */

/** 补零 */
function pad(value: number): string {
  return value.toString().padStart(2, '0')
}

/** ISO 时间 → YYYY-MM-DD HH:mm */
export function formatDateTime(iso: string | undefined | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** ISO 时间 → YYYY-MM-DD */
export function formatDate(iso: string | undefined | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** ISO 时间 → 相对时间（刚刚 / x 分钟前 / x 小时前 / x 天前，超过 7 天回落日期） */
export function formatRelative(iso: string | undefined | null): string {
  if (!iso) return '-'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  const diffMs = Date.now() - date.getTime()
  const minutes = Math.floor(diffMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days <= 7) return `${days} 天前`
  return formatDate(iso)
}

/** 今天的日期（YYYY-MM-DD，统计查询默认区间用） */
export function todayISO(): string {
  return formatDate(new Date().toISOString())
}

/** N 天前的日期（YYYY-MM-DD） */
export function subDays(days: number): string {
  const date = new Date(Date.now() - days * 86400000)
  return date.toISOString()
}
