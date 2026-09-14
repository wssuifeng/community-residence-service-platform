<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import AdminStatCard from '@/components/admin/AdminStatCard.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { getCommunityList, getResourceList } from '@/api/community'
import {
  completeReservation,
  confirmReservation,
  listAvailableTimeslots,
  listReservations,
  listResidentViolations,
  rejectReservation,
  violateReservation
} from '@/api/reservation'
import type {
  IAvailableTimeslot,
  IResourceReservation,
  IViolationRecord,
  ReservationStatus
} from '@/types/modules/reservation'
import { reservationStatusLabels, violationTypeLabels } from '@/types/modules/reservation'
import type { IPublicResource } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'

/**
 * 预约管理（R2 任务 A6 改版：资源先行 + 月历视图，替代任务 9 的固定周网格）：
 * ?tab=reservations 内容 = 资源下拉（必选，默认第一个资源）+ 自写月历（周一开头，
 * 月可前后翻 + 回本月；日格徽标 = 当日占用态预约数(PENDING/RESERVED)/容量合计，
 * 饱和度底色 + 待审核黄标）+ 日详情面板（当日时段卡 + 预约卡 + 五动作）。
 * 点日格经 ?date=YYYY-MM-DD 深链承载选中日；?tab=reservations|violations 契约不变；
 * 旧 ?week= 深链迁移映射为该周周一的 ?date=（周视图已退役）。
 * 数据决策：资源下拉聚合全部社区资源（本页无社区筛选能力，沿用任务 9 现状，
 * 重名资源以「名称（社区名）」消歧）；月范围 available-slots + reservations 一次拉取
 * （实测 44 条时段 6.2KB + 预约约 1KB，无需分周懒加载）；日格占用数取 listReservations
 * 占用态口径——后端 available-slots 的 currentBookings 仅统计与模板起止完全一致的
 * 预约，子区间预约不计数（已记报告供后端适配清单评估）；时段详情抽屉并入日详情面板。
 */

const route = useRoute()
const router = useRouter()

/* ===================== 图标（24×24 stroke path） ===================== */

const ICONS: Record<string, string[]> = {
  calendar: [
    'M8 2v4',
    'M16 2v4',
    'M3 10h18',
    'M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z'
  ],
  audit: [
    'M9 2h6a1 1 0 0 1 1 1v2H8V3a1 1 0 0 1 1-1z',
    'M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2',
    'M9 13l2 2 4-4'
  ],
  alert: [
    'M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z',
    'M12 9v4',
    'M12 17h.01'
  ],
  chevronLeft: ['M15 6l-6 6 6 6'],
  chevronRight: ['M9 6l6 6-6 6']
}

/* ===================== 日期工具（ISO 串按 UTC 语义运算，避免时区漂移） ===================== */

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}

function parseISO(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return Date.UTC(y, m - 1, d)
}

function isoOfMS(ms: number): string {
  const date = new Date(ms)
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`
}

function addDaysISO(iso: string, days: number): string {
  return isoOfMS(parseISO(iso) + days * 86400000)
}

/** 归一到该日期所在周的周一（旧 ?week= 深链迁移用） */
function mondayOf(iso: string): string {
  const weekday = new Date(parseISO(iso)).getUTCDay()
  return addDaysISO(iso, -((weekday + 6) % 7))
}

function localTodayISO(): string {
  const now = new Date()
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const localToday = localTodayISO()

/** HH:mm:ss → HH:mm（后端 LocalTime 序列化带秒） */
function hm(time: string): string {
  return time.slice(0, 5)
}

function cnDate(iso: string): string {
  const [, m, d] = iso.split('-')
  return `${Number(m)}月${Number(d)}日`
}

const WEEKDAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

function weekdayLabel(iso: string): string {
  const index = (new Date(parseISO(iso)).getUTCDay() + 6) % 7
  return WEEKDAY_LABELS[index] ?? iso
}

const CAL_WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

function avatarOf(name: string): string {
  return name ? name.slice(0, 1) : '?'
}

/* ===================== Tab 深链 ?tab=reservations|violations（契约逐字保持） ===================== */

const TABS = ['reservations', 'violations'] as const
type TabName = (typeof TABS)[number]

const activeTab = computed<TabName>(() => {
  const tab = route.query.tab
  return typeof tab === 'string' && (TABS as readonly string[]).includes(tab)
    ? (tab as TabName)
    : 'reservations'
})

function switchTab(tab: TabName): void {
  if (tab === activeTab.value) return
  router.replace({ query: { ...route.query, tab } })
}

/* ===================== 资源先行：下拉必选（?resource= 深链，缺省第一个资源） ===================== */

const resources = ref<IPublicResource[]>([])

async function loadResources(): Promise<void> {
  try {
    const communities = await getCommunityList({ page: 1, size: 200 })
    const results = await Promise.allSettled(
      communities.records.map((community) =>
        getResourceList(community.id, { page: 1, size: 200 })
      )
    )
    resources.value = results.flatMap((result) =>
      result.status === 'fulfilled' ? result.value.records : []
    )
  } catch (error) {
    resources.value = []
    ElMessage.error(error instanceof Error ? error.message : '公共资源加载失败')
  }
}

/** 选中资源 ID：?resource= 合法且存在时用之，否则缺省第一个资源（资源必选，无「全部资源」项） */
const resourceId = computed<number | null>(() => {
  const raw = route.query.resource
  const id = typeof raw === 'string' ? Number(raw) : Number.NaN
  if (Number.isInteger(id) && id > 0 && resources.value.some((item) => item.id === id)) {
    return id
  }
  return resources.value[0]?.id ?? null
})

const selectedResource = computed(
  () => resources.value.find((item) => item.id === resourceId.value) ?? null
)

/** 跨社区聚合时重名资源以「名称（社区名）」消歧 */
const duplicatedNames = computed(() => {
  const counts = new Map<string, number>()
  for (const item of resources.value) {
    counts.set(item.name, (counts.get(item.name) ?? 0) + 1)
  }
  return new Set([...counts.entries()].filter(([, count]) => count > 1).map(([name]) => name))
})

function resourceLabel(item: IPublicResource): string {
  return duplicatedNames.value.has(item.name) ? `${item.name}（${item.communityName}）` : item.name
}

function selectResource(value: string | number): void {
  const query = { ...route.query }
  if (value === '' || value === null) {
    delete query.resource
  } else {
    query.resource = String(value)
  }
  router.replace({ query })
}

/* ===================== 月历状态：视图月（本地）+ 选中日（?date= 深链） ===================== */

const viewYear = ref(new Date().getFullYear())
/** 0 起的月份索引 */
const viewMonthIndex = ref(new Date().getMonth())
/** 无 ?date= 时的默认选中日（首载自动选中今天） */
const fallbackSelected = ref<string | null>(localToday)

const queryDate = computed<string | null>(() => {
  const raw = route.query.date
  if (typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(raw) && !Number.isNaN(parseISO(raw))) {
    return raw
  }
  return null
})

/** 选中日：?date= 优先，否则本地默认（今天/空） */
const selectedDate = computed(() => queryDate.value ?? fallbackSelected.value)

const monthStart = computed(
  () => `${viewYear.value}-${pad(viewMonthIndex.value + 1)}-01`
)
const monthEnd = computed(() => isoOfMS(Date.UTC(viewYear.value, viewMonthIndex.value + 1, 0)))
const monthLabel = computed(() => `${viewYear.value}年${viewMonthIndex.value + 1}月`)

function monthKey(iso: string): string {
  return iso.slice(0, 7)
}

function viewKey(): string {
  return `${viewYear.value}-${pad(viewMonthIndex.value + 1)}`
}

function shiftMonth(delta: number): void {
  const next = new Date(viewYear.value, viewMonthIndex.value + delta, 1)
  viewYear.value = next.getFullYear()
  viewMonthIndex.value = next.getMonth()
  const selected = selectedDate.value
  if (selected && monthKey(selected) !== viewKey()) {
    // 翻离选中日所在月：清空选中（含 ?date=），日详情回落到引导空态
    fallbackSelected.value = null
    if (queryDate.value) {
      const query = { ...route.query }
      delete query.date
      router.replace({ query })
    }
  }
}

function gotoThisMonth(): void {
  viewYear.value = Number(localToday.slice(0, 4))
  viewMonthIndex.value = Number(localToday.slice(5, 7)) - 1
  fallbackSelected.value = localToday
  router.replace({ query: { ...route.query, date: localToday } })
}

function selectDate(iso: string): void {
  router.replace({ query: { ...route.query, date: iso } })
}

/** 月历格子：周一开头，空格补齐首周；UTC 语义运算 */
const calendarCells = computed<Array<{ day: number; iso: string } | null>>(() => {
  const first = new Date(Date.UTC(viewYear.value, viewMonthIndex.value, 1))
  const offset = (first.getUTCDay() + 6) % 7
  const daysInMonth = new Date(Date.UTC(viewYear.value, viewMonthIndex.value + 1, 0)).getUTCDate()
  const cells: Array<{ day: number; iso: string } | null> = []
  for (let i = 0; i < offset; i += 1) cells.push(null)
  for (let day = 1; day <= daysInMonth; day += 1) {
    cells.push({ day, iso: `${viewYear.value}-${pad(viewMonthIndex.value + 1)}-${pad(day)}` })
  }
  return cells
})

/* ===================== 月数据：available-slots 骨架 + 预约循环拉全量 ===================== */

const monthSlots = ref<IAvailableTimeslot[]>([])
const monthReservations = ref<IResourceReservation[]>([])
const monthLoading = ref(false)
let monthSeq = 0

/** 后端分页上限 100：月区间预约循环翻页取全量（50 页保险上限，先例 LeaseListView）；
 * 按 id 去重兜底（R2 定性结论的防御惯例），去重计数用 fetched 独立累计防死循环 */
async function fetchMonthReservations(targetId: number): Promise<IResourceReservation[]> {
  const collected: IResourceReservation[] = []
  const seen = new Set<number>()
  let fetched = 0
  let current = 1
  for (;;) {
    const result = await listReservations({
      page: current,
      size: 100,
      resourceId: targetId,
      startDate: monthStart.value,
      endDate: monthEnd.value
    })
    for (const row of result.records) {
      if (seen.has(row.id)) continue
      seen.add(row.id)
      collected.push(row)
    }
    fetched += result.records.length
    if (result.records.length === 0 || fetched >= result.total || current >= 50) break
    current += 1
  }
  return collected
}

async function loadMonth(): Promise<void> {
  const seq = ++monthSeq
  monthLoading.value = true
  const targetId = resourceId.value
  if (targetId === null) {
    if (seq === monthSeq) {
      monthSlots.value = []
      monthReservations.value = []
      monthLoading.value = false
    }
    return
  }
  try {
    const [slotList, reservations] = await Promise.all([
      listAvailableTimeslots(targetId, { startDate: monthStart.value, endDate: monthEnd.value }),
      fetchMonthReservations(targetId)
    ])
    if (seq !== monthSeq) return
    monthSlots.value = slotList
      .slice()
      .sort((a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime))
    monthReservations.value = reservations.sort((a, b) =>
      `${a.reserveDate} ${a.startTime}`.localeCompare(`${b.reserveDate} ${b.startTime}`)
    )
  } catch (error) {
    if (seq === monthSeq) {
      monthSlots.value = []
      monthReservations.value = []
      ElMessage.error(error instanceof Error ? error.message : '预约月历加载失败')
    }
  } finally {
    if (seq === monthSeq) monthLoading.value = false
  }
}

/* ===================== 日格聚合：占用数/容量/待审核（占用口径见文件头） ===================== */

interface DayAgg {
  capacity: number
  active: number
  pending: number
  full: boolean
}

const dayAggs = computed<Map<string, DayAgg>>(() => {
  const map = new Map<string, DayAgg>()
  const ensure = (iso: string): DayAgg => {
    let agg = map.get(iso)
    if (!agg) {
      agg = { capacity: 0, active: 0, pending: 0, full: false }
      map.set(iso, agg)
    }
    return agg
  }
  for (const slot of monthSlots.value) {
    const agg = ensure(slot.date)
    agg.capacity += slot.maxBookings
    if (slot.status === 'FULL') agg.full = true
  }
  for (const row of monthReservations.value) {
    if (row.status !== 'PENDING' && row.status !== 'RESERVED' && row.status !== 'CONFIRMED') {
      continue
    }
    const agg = ensure(row.reserveDate)
    agg.active += 1
    if (row.status === 'PENDING') agg.pending += 1
  }
  return map
})

type DayTint = 'is-off' | 'is-free' | 'is-some' | 'is-full'

/** 饱和度底色：无时段灰 > 占用≥容量深 > 有占用蓝 > 空闲白 */
function dayTint(iso: string): DayTint {
  const agg = dayAggs.value.get(iso)
  if (!agg || agg.capacity === 0) return 'is-off'
  if (agg.full || agg.active >= agg.capacity) return 'is-full'
  if (agg.active > 0) return 'is-some'
  return 'is-free'
}

function dayBadgeText(iso: string): string {
  const agg = dayAggs.value.get(iso)
  if (!agg || agg.capacity === 0) return ''
  return `${agg.active}/${agg.capacity}`
}

function dayPendingCount(iso: string): number {
  return dayAggs.value.get(iso)?.pending ?? 0
}

/* ===================== 日详情面板：当日时段卡 + 预约卡（并入原时段抽屉） ===================== */

const daySlots = computed(() =>
  selectedDate.value
    ? monthSlots.value.filter((slot) => slot.date === selectedDate.value)
    : []
)

const dayReservations = computed(() =>
  selectedDate.value
    ? monthReservations.value.filter((row) => row.reserveDate === selectedDate.value)
    : []
)

/* ===================== 统计卡（真实口径，见文件头数据决策） ===================== */

const todayTotal = ref(0)
const pendingTotal = ref(0)
const violatedTotal = ref(0)

async function loadStats(): Promise<void> {
  try {
    const [todayResult, violatedResult] = await Promise.all([
      listReservations({ page: 1, size: 1, startDate: localToday, endDate: localToday }),
      listReservations({ page: 1, size: 1, status: 'VIOLATED' })
    ])
    todayTotal.value = todayResult.total
    violatedTotal.value = violatedResult.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '统计加载失败')
  }
}

/* ===================== 待审核右栏（全量 PENDING，不限当月） ===================== */

const pendingItems = ref<IResourceReservation[]>([])
const pendingLoading = ref(false)
let pendingSeq = 0

async function loadPending(): Promise<void> {
  const seq = ++pendingSeq
  pendingLoading.value = true
  try {
    const collected: IResourceReservation[] = []
    let current = 1
    for (;;) {
      const result = await listReservations({ page: current, size: 100, status: 'PENDING' })
      if (seq !== pendingSeq) return
      collected.push(...result.records)
      pendingTotal.value = result.total
      if (result.records.length === 0 || collected.length >= result.total || current >= 5) break
      current += 1
    }
    collected.sort((a, b) =>
      `${a.reserveDate} ${a.startTime}`.localeCompare(`${b.reserveDate} ${b.startTime}`)
    )
    pendingItems.value = collected
  } catch (error) {
    if (seq === pendingSeq) {
      pendingItems.value = []
      ElMessage.error(error instanceof Error ? error.message : '待审核队列加载失败')
    }
  } finally {
    if (seq === pendingSeq) pendingLoading.value = false
  }
}

/* ===================== 状态语义翻译 ===================== */

function statusTagType(
  status: ReservationStatus
): 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled' {
  if (status === 'PENDING') return 'pending'
  if (status === 'RESERVED' || status === 'CONFIRMED') return 'processing'
  if (status === 'COMPLETED') return 'completed'
  if (status === 'REJECTED' || status === 'VIOLATED') return 'rejected'
  return 'canceled'
}

function statusText(status: ReservationStatus): string {
  return reservationStatusLabels[status] ?? status
}

/* ===================== 审核与处置（五动作 reason 后端 @NotBlank，统一弹窗收集） ===================== */

async function promptReason(
  title: string,
  message: string,
  placeholder: string,
  options?: { defaultValue?: string; confirmButtonText?: string; type?: 'success' | 'warning' | 'error' | 'info' }
): Promise<string | null> {
  try {
    const { value } = await ElMessageBox.prompt(message, title, {
      confirmButtonText: options?.confirmButtonText ?? '确定',
      cancelButtonText: '取消',
      inputPlaceholder: placeholder,
      inputValue: options?.defaultValue ?? '',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '原因不能为空'),
      type: options?.type ?? 'info'
    })
    return value.trim()
  } catch {
    return null
  }
}

/** 动作成功后月历徽标/待审核/统计全量联动刷新 */
async function afterAction(): Promise<void> {
  await Promise.all([loadMonth(), loadPending(), loadStats()])
}

async function handleConfirm(row: IResourceReservation): Promise<void> {
  const reason = await promptReason(
    `审核通过 · ${row.resourceName}`,
    `${row.userName || `居民 ${row.userId}`} · ${row.reserveDate} ${hm(row.startTime)}~${hm(row.endTime)}`,
    '审核备注（如：同意预约）',
    { confirmButtonText: '通过', defaultValue: '同意', type: 'success' }
  )
  if (reason === null) return
  try {
    await confirmReservation(row.id, { reason })
    ElMessage.success('已通过审核')
    await afterAction()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审核操作失败')
  }
}

async function handleReject(row: IResourceReservation): Promise<void> {
  const reason = await promptReason(
    `拒绝预约 · ${row.resourceName}`,
    '拒绝后居民将收到通知，理由必填',
    '例如：该时段资源维护，暂停开放',
    { confirmButtonText: '确认拒绝', type: 'warning' }
  )
  if (reason === null) return
  try {
    await rejectReservation(row.id, { reason })
    ElMessage.success('已拒绝该预约')
    await afterAction()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '拒绝操作失败')
  }
}

async function handleComplete(row: IResourceReservation): Promise<void> {
  const reason = await promptReason(
    `完成预约 · ${row.resourceName}`,
    `确认「${row.resourceName}」${row.reserveDate} 的预约已完成使用？`,
    '完成备注（如：使用完毕）',
    { confirmButtonText: '确认完成', defaultValue: '使用完毕', type: 'success' }
  )
  if (reason === null) return
  try {
    await completeReservation(row.id, { reason })
    ElMessage.success('已标记完成')
    await afterAction()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '完成操作失败')
  }
}

async function handleViolate(row: IResourceReservation): Promise<void> {
  const reason = await promptReason(
    `标记违约 · ${row.resourceName}`,
    '违约将计入该居民的违约记录，达到上限将自动冻结账号',
    '违约原因（如：预约后未到场）',
    { confirmButtonText: '确认标记违约', type: 'error' }
  )
  if (reason === null) return
  try {
    await violateReservation(row.id, { reason })
    ElMessage.success('已标记违约')
    await afterAction()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '违约标记失败')
  }
}

/* ===================== 违约记录 Tab（原 ViolationListView 收编，本轮未动） ===================== */

const violationQuery = reactive({ page: 1, size: 10 })
const residentIdInput = ref<number | null>(null)
const searchedResidentId = ref<number | null>(null)
const violationRecords = ref<IViolationRecord[]>([])
const violationTotal = ref(0)
const violationLoading = ref(false)

const violationEmpty = computed(
  () =>
    !violationLoading.value &&
    searchedResidentId.value !== null &&
    violationRecords.value.length === 0
)

async function loadViolations(): Promise<void> {
  if (searchedResidentId.value === null) return
  violationLoading.value = true
  try {
    const result = await listResidentViolations(searchedResidentId.value, {
      page: violationQuery.page,
      size: violationQuery.size
    })
    violationRecords.value = result.records
    violationTotal.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载违约记录失败')
  } finally {
    violationLoading.value = false
  }
}

function handleViolationSearch(): void {
  if (residentIdInput.value === null) {
    ElMessage.warning('请先输入要查询的居民 ID')
    return
  }
  searchedResidentId.value = residentIdInput.value
  violationQuery.page = 1
  loadViolations()
}

/** 类型标签：后端写入值 RESERVATION_NO_SHOW/VIEWING_NO_SHOW，未知值回落原文 */
function violationTypeText(value: string): string {
  return (violationTypeLabels as Record<string, string>)[value] ?? value
}

/* ===================== 初始化与联动刷新 ===================== */

async function refreshData(): Promise<void> {
  await Promise.all([loadMonth(), loadPending(), loadStats()])
}

/** 首载 hydration 完成前抑制 watch，避免与 onMounted 的 refreshData 重复拉取 */
let monthWatchReady = false

watch([resourceId, monthStart], () => {
  if (!monthWatchReady) return
  if (activeTab.value === 'reservations') loadMonth()
})

/** ?date= 深链（含浏览器前进/后退）：视图月同步到选中日所在月 */
watch(queryDate, (iso) => {
  if (!iso) return
  const [y, m] = iso.split('-').map(Number)
  if (viewYear.value !== y || viewMonthIndex.value !== m - 1) {
    viewYear.value = y
    viewMonthIndex.value = m - 1
  }
})

onMounted(async () => {
  // 旧 ?week= 深链兼容：周视图退役，映射为该周周一的 ?date=（已有 ?date= 时仅清除 week）
  const weekRaw = route.query.week
  if (typeof weekRaw === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(weekRaw) && !Number.isNaN(parseISO(weekRaw))) {
    const query = { ...route.query }
    delete query.week
    if (!queryDate.value) query.date = mondayOf(weekRaw)
    await router.replace({ query })
  }
  // 初始视图月与默认选中：?date= 优先，其次今天
  const initDate = queryDate.value ?? localToday
  viewYear.value = Number(initDate.slice(0, 4))
  viewMonthIndex.value = Number(initDate.slice(5, 7)) - 1
  fallbackSelected.value = queryDate.value ? null : localToday
  await loadResources()
  await refreshData()
  monthWatchReady = true
})
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="预约管理" subtitle="公共资源预约审核、时段占用与违约处置">
      <template v-if="activeTab === 'reservations'">
        <el-select
          class="toolbar-resource"
          :model-value="resourceId ?? ''"
          placeholder="选择公共资源"
          @change="selectResource"
        >
          <el-option
            v-for="item in resources"
            :key="item.id"
            :label="resourceLabel(item)"
            :value="item.id"
          />
        </el-select>
        <div class="month-nav" role="group" aria-label="月份切换">
          <button type="button" class="month-nav-btn" aria-label="上一月" @click="shiftMonth(-1)">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                v-for="(d, i) in ICONS.chevronLeft"
                :key="i"
                :d="d"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
          <button type="button" class="month-nav-btn is-text" @click="gotoThisMonth">本月</button>
          <span class="month-label">{{ monthLabel }}</span>
          <button type="button" class="month-nav-btn" aria-label="下一月" @click="shiftMonth(1)">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                v-for="(d, i) in ICONS.chevronRight"
                :key="i"
                :d="d"
                stroke="currentColor"
                stroke-width="1.8"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
          </button>
        </div>
      </template>
    </AdminPageHeader>

    <!-- 3 统计卡：第三列与右栏待审核队列对齐（对照稿布局） -->
    <div class="stat-row">
      <AdminStatCard label="今日预约" :value="todayTotal" unit="单" :icon="ICONS.calendar" accent="primary" />
      <AdminStatCard label="待审核" :value="pendingTotal" unit="单" :icon="ICONS.audit" accent="warning" />
      <AdminStatCard label="累计违约" :value="violatedTotal" unit="次" :icon="ICONS.alert" accent="danger" />
    </div>

    <!-- Tab 条：?tab= 深链契约（reservations|violations） -->
    <nav class="tab-bar" role="tablist" aria-label="预约管理视图切换">
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'reservations' }"
        :aria-selected="activeTab === 'reservations'"
        @click="switchTab('reservations')"
      >
        预约月历
      </button>
      <button
        type="button"
        role="tab"
        class="tab-item"
        :class="{ active: activeTab === 'violations' }"
        :aria-selected="activeTab === 'violations'"
        @click="switchTab('violations')"
      >
        违约记录
      </button>
    </nav>

    <!-- ==================== Tab 1：资源先行 + 月历 + 日详情 + 待审核右栏 ==================== -->
    <template v-if="activeTab === 'reservations'">
      <div class="reservation-layout">
        <section class="panel-card calendar-panel">
          <header class="panel-head">
            <h2 class="panel-title">{{ selectedResource?.name ?? '公共资源' }} · 预约月历</h2>
            <span class="panel-hint">点日期查看当日详情</span>
          </header>

          <div v-loading="monthLoading" class="calendar-body">
            <el-empty
              v-if="resources.length === 0"
              description="暂无公共资源，请先在资源管理中创建资源并配置可预约时段"
              :image-size="88"
            />
            <template v-else>
              <div class="cal-weekdays" aria-hidden="true">
                <span v-for="w in CAL_WEEKDAYS" :key="w">{{ w }}</span>
              </div>
              <div class="cal-grid">
                <button
                  v-for="(cell, index) in calendarCells"
                  :key="index"
                  type="button"
                  class="cal-cell"
                  :class="[
                    cell ? dayTint(cell.iso) : 'is-blank',
                    {
                      'is-today': cell?.iso === localToday,
                      'is-selected': cell !== null && cell.iso === selectedDate
                    }
                  ]"
                  :data-date="cell?.iso"
                  :disabled="cell === null"
                  @click="cell && selectDate(cell.iso)"
                >
                  <template v-if="cell">
                    <span class="cell-day">{{ cell.day }}</span>
                    <span v-if="dayBadgeText(cell.iso)" class="cell-badge">
                      {{ dayBadgeText(cell.iso) }}
                    </span>
                    <span v-if="dayPendingCount(cell.iso) > 0" class="cell-pending">
                      待审 {{ dayPendingCount(cell.iso) }}
                    </span>
                  </template>
                </button>
              </div>
              <div class="cal-legend" aria-hidden="true">
                <span class="legend-item"><i class="legend-dot is-free"></i>空闲</span>
                <span class="legend-item"><i class="legend-dot is-some"></i>有预约</span>
                <span class="legend-item"><i class="legend-dot is-full"></i>已约满</span>
                <span class="legend-item"><i class="legend-dot is-off"></i>无时段</span>
                <span class="legend-item"><i class="legend-dot is-pending"></i>有待审核</span>
              </div>
            </template>
          </div>
        </section>

        <section class="panel-card day-panel">
          <template v-if="selectedDate">
            <header class="panel-head">
              <h2 class="panel-title">{{ cnDate(selectedDate) }} · {{ weekdayLabel(selectedDate) }}</h2>
              <span class="panel-hint">{{ selectedResource?.name ?? '' }}</span>
            </header>
            <div v-loading="monthLoading" class="day-body">
              <h3 class="day-subtitle">当日时段（{{ daySlots.length }}）</h3>
              <div v-if="daySlots.length > 0" class="day-slots">
                <div
                  v-for="slot in daySlots"
                  :key="`${slot.timeslotId}-${slot.startTime}`"
                  class="day-slot"
                  :class="{ 'is-full': slot.status === 'FULL' }"
                >
                  <span class="day-slot-time">{{ hm(slot.startTime) }}-{{ hm(slot.endTime) }}</span>
                  <span class="day-slot-count">已约 {{ slot.currentBookings }}/{{ slot.maxBookings }}</span>
                  <StatusTag
                    :label="slot.status === 'FULL' ? '已约满' : '可预约'"
                    :type="slot.status === 'FULL' ? 'canceled' : 'completed'"
                  />
                </div>
              </div>
              <p v-else class="day-note">当日无开放时段</p>

              <h3 class="day-subtitle">当日预约（{{ dayReservations.length }}）</h3>
              <article v-for="row in dayReservations" :key="row.id" class="day-item">
                <div class="day-item-head">
                  <span class="pending-avatar" aria-hidden="true">{{ avatarOf(row.userName) }}</span>
                  <span class="day-item-name">{{ row.userName || `居民 ${row.userId}` }}</span>
                  <StatusTag :label="statusText(row.status)" :type="statusTagType(row.status)" />
                </div>
                <dl class="day-item-meta">
                  <div class="meta-item">
                    <dt>预约时间</dt>
                    <dd>{{ hm(row.startTime) }}-{{ hm(row.endTime) }}</dd>
                  </div>
                  <div class="meta-item">
                    <dt>用途</dt>
                    <dd>{{ row.purpose || '-' }}</dd>
                  </div>
                  <div class="meta-item">
                    <dt>联系电话</dt>
                    <dd>{{ row.contactPhone || '-' }}</dd>
                  </div>
                  <div v-if="row.remark" class="meta-item">
                    <dt>备注</dt>
                    <dd>{{ row.remark }}</dd>
                  </div>
                </dl>
                <div class="day-item-actions">
                  <template v-if="row.status === 'PENDING'">
                    <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" size="small" @click="handleConfirm(row)">
                      通过
                    </el-button>
                    <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="danger" plain size="small" @click="handleReject(row)">
                      拒绝
                    </el-button>
                  </template>
                  <template v-else-if="row.status === 'RESERVED' || row.status === 'CONFIRMED'">
                    <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="success" size="small" @click="handleComplete(row)">
                      完成
                    </el-button>
                    <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="warning" plain size="small" @click="handleViolate(row)">
                      违约
                    </el-button>
                  </template>
                  <span v-else class="day-no-action">终态，无可用操作</span>
                </div>
              </article>
              <el-empty
                v-if="dayReservations.length === 0"
                description="当日暂无预约记录"
                :image-size="64"
              />
            </div>
          </template>
          <el-empty v-else description="点击左侧月历日期，查看当日时段与预约" :image-size="88" />
        </section>

        <aside class="panel-card pending-panel">
          <header class="panel-head">
            <h2 class="panel-title">
              待审核
              <span v-if="pendingTotal > 0" class="pending-badge">{{ pendingTotal }}</span>
            </h2>
          </header>
          <div v-loading="pendingLoading" class="pending-list">
            <article v-for="item in pendingItems" :key="item.id" class="pending-card">
              <div class="pending-head">
                <span class="pending-avatar" aria-hidden="true">{{ avatarOf(item.userName) }}</span>
                <div class="pending-who">
                  <span class="pending-name">{{ item.userName || `居民 ${item.userId}` }}</span>
                  <span class="pending-resource">{{ item.resourceName }} · {{ weekdayLabel(item.reserveDate) }}</span>
                </div>
              </div>
              <p class="pending-when">{{ cnDate(item.reserveDate) }} {{ hm(item.startTime) }}-{{ hm(item.endTime) }}</p>
              <p v-if="item.purpose" class="pending-purpose">用途：{{ item.purpose }}</p>
              <div class="pending-actions">
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" size="small" @click="handleConfirm(item)">
                  通过
                </el-button>
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="danger" plain size="small" @click="handleReject(item)">
                  拒绝
                </el-button>
              </div>
            </article>
            <el-empty
              v-if="!pendingLoading && pendingItems.length === 0"
              description="暂无待审核预约"
              :image-size="72"
            />
          </div>
        </aside>
      </div>

      <!-- 对照稿底部违约入口：违约接口仅支持按居民查询（预检核实），故不展示总数 -->
      <button type="button" class="violation-entry" @click="switchTab('violations')">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            v-for="(d, i) in ICONS.alert"
            :key="i"
            :d="d"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        违约记录 · 按居民查询违约档案
        <svg class="entry-chevron" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            v-for="(d, i) in ICONS.chevronRight"
            :key="i"
            :d="d"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
    </template>

    <!-- ==================== Tab 2：违约记录（原 ViolationListView 收编） ==================== -->
    <section v-else class="panel-card violation-panel">
      <div class="violation-toolbar">
        <el-input-number
          v-model="residentIdInput"
          :min="1"
          :controls="false"
          placeholder="输入居民 ID"
          class="violation-input"
          @keyup.enter="handleViolationSearch"
        />
        <el-button type="primary" @click="handleViolationSearch">查询</el-button>
        <span class="violation-tip">违约由预约/看房的「违约」处置登记，此处按居民查询其违约档案</span>
      </div>

      <template v-if="searchedResidentId === null">
        <div class="empty-state">
          <img src="/images/empty-state.png" alt="请输入居民 ID" />
          <p>输入居民 ID 后查询其违约记录</p>
        </div>
      </template>
      <template v-else>
        <el-table v-loading="violationLoading" :data="violationRecords" stripe>
          <el-table-column prop="id" label="记录ID" width="90" />
          <el-table-column prop="residentId" label="居民ID" width="90" />
          <el-table-column label="违约类型" width="110" align="center">
            <template #default="{ row }">
              <StatusTag
                :label="violationTypeText(row.violationType)"
                :type="row.violationType === 'VIEWING_NO_SHOW' ? 'info' : 'processing'"
              />
            </template>
          </el-table-column>
          <el-table-column prop="relatedId" label="关联单据ID" width="110">
            <template #default="{ row }">{{ row.relatedId ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="处置" min-width="110">
            <template #default="{ row }">{{ row.punishment || '-' }}</template>
          </el-table-column>
          <el-table-column label="违约原因" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.remark || '-' }}</template>
          </el-table-column>
          <el-table-column label="登记时间" min-width="160">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>

        <div v-if="violationEmpty" class="table-empty-hint">该居民暂无违约记录</div>

        <Pagination
          v-model:page="violationQuery.page"
          v-model:size="violationQuery.size"
          :total="violationTotal"
          @update:page="loadViolations"
          @update:size="loadViolations"
        />
      </template>
    </section>
  </div>
</template>

<style scoped>
/* ---------- 页头工具区：资源下拉 + 月份切换 ---------- */

.toolbar-resource {
  width: 200px;
}

.month-nav {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: 3px;
  background-color: var(--admin-card-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
}

.month-nav-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 28px;
  min-width: 28px;
  padding: 0 var(--spacing-xs);
  border: none;
  border-radius: var(--radius-sm);
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.month-nav-btn svg {
  width: 15px;
  height: 15px;
}

.month-nav-btn:hover {
  background-color: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.month-nav-btn.is-text {
  padding: 0 var(--spacing-sm);
  color: var(--color-primary);
}

.month-label {
  padding: 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  white-space: nowrap;
}

/* ---------- 统计卡与内容栅格（第三列对齐右栏 320px） ---------- */

.stat-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 320px;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
}

/* ---------- Tab 条（与租住管理同款白卡下划线式） ---------- */

.tab-bar {
  display: flex;
  align-items: stretch;
  gap: var(--spacing-xl);
  padding: 0 var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow-x: auto;
}

.tab-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
  height: 56px;
  padding: 0 var(--spacing-xs);
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-md);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease;
}

.tab-item:hover {
  color: var(--color-text-primary);
}

.tab-item.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

/* ---------- 主布局：月历 340px + 日详情 1fr + 待审核右栏 320px ---------- */

.reservation-layout {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr) 320px;
  gap: var(--spacing-lg);
  align-items: start;
}

.panel-card {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.panel-title {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 月历网格 ---------- */

.cal-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  margin-bottom: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: center;
}

.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: var(--spacing-xs);
}

.cal-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-height: 64px;
  padding: var(--spacing-xs) 0;
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: none;
  font-family: inherit;
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.cal-cell.is-blank {
  cursor: default;
}

.cal-cell:not(.is-blank):hover {
  border-color: var(--color-primary-light);
}

.cell-day {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1.5px solid transparent;
  border-radius: var(--radius-circle);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.cal-cell.is-today .cell-day {
  border-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
}

.cal-cell.is-selected {
  border-color: var(--color-primary);
}

.cal-cell.is-selected .cell-day {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--admin-card-bg);
}

.cell-badge {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
  line-height: var(--line-height-tight);
}

.cell-pending {
  padding: 0 3px;
  border-radius: var(--radius-pill);
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  white-space: nowrap;
}

/* 饱和度底色：无时段灰 / 空闲白卡 / 有预约蓝 / 已约满深灰 */

.cal-cell.is-off {
  background-color: var(--color-bg-subtle);
}

.cal-cell.is-off .cell-day {
  color: var(--color-text-disabled);
}

.cal-cell.is-free {
  background-color: var(--admin-card-bg);
  border-color: var(--color-border);
}

.cal-cell.is-some {
  background-color: var(--color-primary-bg);
}

.cal-cell.is-full {
  background-color: var(--color-bg-hover);
}

.cal-cell.is-full .cell-day,
.cal-cell.is-full .cell-badge {
  color: var(--color-text-disabled);
}

.cal-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin-top: var(--spacing-md);
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.legend-dot {
  width: 10px;
  height: 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
}

.legend-dot.is-free {
  background-color: var(--admin-card-bg);
  border-color: var(--color-border);
}

.legend-dot.is-some {
  background-color: var(--color-primary-bg);
}

.legend-dot.is-full {
  background-color: var(--color-bg-hover);
}

.legend-dot.is-off {
  background-color: var(--color-bg-subtle);
}

.legend-dot.is-pending {
  background-color: var(--color-warning-soft);
  border-color: var(--color-warning);
}

/* ---------- 日详情面板 ---------- */

.day-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.day-subtitle {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.day-slots {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.day-slot {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.day-slot-time {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.day-slot-count {
  flex: 1;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.day-slot.is-full .day-slot-time,
.day-slot.is-full .day-slot-count {
  color: var(--color-text-disabled);
}

.day-note {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.day-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.day-item-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.day-item-head .pending-avatar {
  width: 28px;
  height: 28px;
  font-size: var(--font-size-xs);
}

.day-item-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.day-item-meta {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin: 0;
}

.meta-item {
  display: flex;
  gap: var(--spacing-sm);
  font-size: var(--font-size-xs);
}

.meta-item dt {
  flex-shrink: 0;
  width: 56px;
  color: var(--color-text-secondary);
}

.meta-item dd {
  margin: 0;
  color: var(--color-text-primary);
  word-break: break-word;
}

.day-item-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.day-no-action {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* ---------- 待审核右栏 ---------- */

.pending-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 var(--spacing-xs);
  border-radius: var(--radius-pill);
  background-color: var(--color-warning-soft);
  color: var(--color-warning);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

.pending-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  max-height: 660px;
  overflow-y: auto;
}

.pending-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.pending-card:hover {
  border-color: var(--color-primary-light);
  box-shadow: var(--shadow-card);
}

.pending-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.pending-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
}

.pending-who {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.pending-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.pending-resource {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-when {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.pending-purpose {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

/* ---------- 底部违约入口（对照稿底部条） ---------- */

.violation-entry {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-lg);
  padding: var(--spacing-sm) 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.15s ease;
}

.violation-entry svg {
  width: 15px;
  height: 15px;
}

.violation-entry:hover {
  color: var(--color-primary);
}

/* ---------- 违约记录 Tab ---------- */

.violation-toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-md);
}

.violation-input {
  width: 160px;
}

.violation-tip {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.empty-state {
  padding: var(--spacing-xxl) var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state img {
  width: 120px;
  margin: 0 auto var(--spacing-md);
}

.table-empty-hint {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .stat-row {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .reservation-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 767px) {
  .stat-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
