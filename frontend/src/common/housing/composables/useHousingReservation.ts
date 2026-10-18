import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getHousingDetail, listViewingAvailableSlots, createViewingAppointment } from '@/common/housing/api/housing'
import { getConversationViewingContext, listConversationViewingSlots, createConversationViewing } from '@/common/conversation/api/conversation'
import { getMyProfile } from '@/common/resident/api/resident'
import type { IHousing, IAvailableViewingTimeslot } from '@/common/housing/types/housing'
import type { IResident } from '@/common/resident/types/resident'

export interface HousingReservationContext { housingId?: number; conversationId?: number }

/** 同一预约规则供居民路由页与会话弹窗使用，导航由入口承担。 */
export function useHousingReservation(context: HousingReservationContext, onCreated: (id?: number) => void, onInvalid?: () => void) {


/**
 * 预约看房（DEF-059/R60，v1.3）：交互复制资源预约三步模式（选日期 → 选时段 → 确认）。
 * 入口显式传入 housingId 或 conversationId；第 2 步时段卡多选，仅允许相邻连续时段合并提交（R60）：
 * 非连续点击拒绝并提示，本地连续时长上限 MAX_CONTINUOUS_MINUTES 前置拦截，
 * 最终超限由后端按 sys_config 兜底拒绝；提交为合并区间单条预约，成功跳预约详情页。
 *
 * 联系人身份由登录居民账号确定，不再收集游客姓名或短信验证码信息。
 * 连续多选规则与 ReservationCreateView（资源域）为同规则双实现（R60 两域各一键），
 * 一侧调整约束须同步另一侧。
 */


const conversationId=context.conversationId
let housingId = Number(context.housingId)
/** 无 housingId 直达（旧书签/坏链）→ 回房源列表 */
const invalidEntry = conversationId ? !Number.isInteger(conversationId) || conversationId<=0 : !Number.isInteger(housingId) || housingId <= 0

const housing = ref<IHousing | null>(null)
const profile = ref<IResident | null>(null)

/* ------------------------------ 步骤轨 ------------------------------ */

const STEPS = [
  { step: 1, title: '选择日期', desc: '在日历上选择看房日期' },
  { step: 2, title: '选择时段', desc: '可多选相邻连续时段' },
  { step: 3, title: '确认提交', desc: '核对信息并提交预约' }
] as const

const step = ref(1)

/** 步骤回跳仅限已解锁步骤（第 2 步需已选日期，第 3 步需已选时段） */
function canGoto(target: number): boolean {
  if (target === 1) return true
  if (target === 2) return selectedDate.value !== null
  return selectedSlots.value.length > 0
}

function gotoStep(target: number): void {
  if (target < step.value && canGoto(target)) {
    step.value = target
  }
}

/* ------------------------------ 第一步：月历选日期 ------------------------------ */

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

function toISODate(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

const today = new Date()
const todayISO = toISODate(today.getFullYear(), today.getMonth(), today.getDate())

const viewYear = ref(today.getFullYear())
const viewMonth = ref(today.getMonth())
const selectedDate = ref<string | null>(null)
const monthSlots = ref<IAvailableViewingTimeslot[]>([])
const monthLoading = ref(false)

const isCurrentMonth = computed(
  () => viewYear.value === today.getFullYear() && viewMonth.value === today.getMonth()
)

/** 月历格子：周一开头，空位补齐首周 */
const calendarCells = computed(() => {
  const first = new Date(viewYear.value, viewMonth.value, 1)
  const offset = (first.getDay() + 6) % 7
  const daysInMonth = new Date(viewYear.value, viewMonth.value + 1, 0).getDate()
  const cells: Array<{ day: number; iso: string } | null> = []
  for (let i = 0; i < offset; i += 1) cells.push(null)
  for (let day = 1; day <= daysInMonth; day += 1) {
    cells.push({ day, iso: toISODate(viewYear.value, viewMonth.value, day) })
  }
  return cells
})

/** 按日期索引当月时段 */
const slotsByDate = computed(() => {
  const map = new Map<string, IAvailableViewingTimeslot[]>()
  monthSlots.value.forEach((slot) => {
    const list = map.get(slot.date) ?? []
    list.push(slot)
    map.set(slot.date, list)
  })
  return map
})

/** 日期可选态：过去/无开放时段/全部约满禁选 */
function dayState(iso: string): 'past' | 'closed' | 'full' | 'open' {
  if (iso < todayISO) return 'past'
  const list = slotsByDate.value.get(iso)
  if (!list || list.length === 0) return 'closed'
  return list.some((slot) => !slotFull(slot)) ? 'open' : 'full'
}

/** 全月无可约提示（R60 口径：日期选择范围以接口返回数据为准，数据不足时前翻下月） */
const monthHasOpen = computed(() =>
  calendarCells.value.some((cell) => cell !== null && dayState(cell.iso) === 'open')
)

function shiftMonth(delta: number): void {
  const next = new Date(viewYear.value, viewMonth.value + delta, 1)
  viewYear.value = next.getFullYear()
  viewMonth.value = next.getMonth()
}

function backToCurrentMonth(): void {
  viewYear.value = today.getFullYear()
  viewMonth.value = today.getMonth()
}

/** 月份切换重查当月时段（本月口径从今天起查，不回看过去）；序号令牌防慢响应回填旧月数据 */
let monthSeq = 0
async function loadMonthSlots(): Promise<void> {
  const seq = monthSeq + 1
  monthSeq = seq
  const first = toISODate(viewYear.value, viewMonth.value, 1)
  const last = toISODate(viewYear.value, viewMonth.value, new Date(viewYear.value, viewMonth.value + 1, 0).getDate())
  monthLoading.value = true
  try {
    const data = await (conversationId ? listConversationViewingSlots(conversationId, {
      startDate: isCurrentMonth.value ? todayISO : first,
      endDate: last
    }) : listViewingAvailableSlots(housingId, { startDate:isCurrentMonth.value?todayISO:first,endDate:last }))
    if (seq === monthSeq) monthSlots.value = data
  } catch (error) {
    if (seq === monthSeq) {
      monthSlots.value = []
      ElMessage.error(error instanceof Error ? error.message : '加载可约时段失败')
    }
  } finally {
    if (seq === monthSeq) monthLoading.value = false
  }
}

watch([viewYear, viewMonth], () => {
  void loadMonthSlots()
})

/** 选中日期 → 进入第二步（重新拉当日时段保证余量新鲜） */
function pickDate(iso: string): void {
  if (dayState(iso) !== 'open') return
  selectedDate.value = iso
  selectedSlots.value = []
  void openStep2()
}

/* ------------------------------ 第二步：时段卡连续多选（R60 核心） ------------------------------ */

/**
 * 本地连续时长上限（分钟）：与 sys_config viewing.max_continuous_minutes 默认值 120 对齐
 * （R60 两域各一键，本页为看房域）；本地仅做前置拦截，超限最终由后端兜底拒绝
 */
const MAX_CONTINUOUS_MINUTES = 120

const daySlots = ref<IAvailableViewingTimeslot[]>([])
const dayLoading = ref(false)
const selectedSlots = ref<IAvailableViewingTimeslot[]>([])

let daySeq = 0
async function openStep2(): Promise<void> {
  if (!selectedDate.value) return
  step.value = 2
  const seq = daySeq + 1
  daySeq = seq
  dayLoading.value = true
  try {
    const data = await (conversationId ? listConversationViewingSlots(conversationId, {
      startDate: selectedDate.value,
      endDate: selectedDate.value
    }) : listViewingAvailableSlots(housingId, {startDate:selectedDate.value,endDate:selectedDate.value}))
    if (seq === daySeq) {
      /* 后端模板返回顺序不保证，按开始时间排序保证卡片展示稳定 */
      daySlots.value = data.sort((a, b) => a.startTime.localeCompare(b.startTime))
    }
  } catch (error) {
    if (seq === daySeq) {
      daySlots.value = []
      ElMessage.error(error instanceof Error ? error.message : '加载当日时段失败')
    }
  } finally {
    if (seq === daySeq) dayLoading.value = false
  }
}

/** 约满/停开禁选：状态非可约或余量耗尽一律置灰 */
function slotFull(slot: IAvailableViewingTimeslot): boolean {
  return slot.status !== 'AVAILABLE' || slot.currentBookings >= slot.maxBookings
}

function slotLabel(slot: IAvailableViewingTimeslot): string {
  return `${slot.startTime.slice(0, 5)} ~ ${slot.endTime.slice(0, 5)}`
}

const allDaySlotsFull = computed(
  () => daySlots.value.length > 0 && daySlots.value.every((slot) => slotFull(slot))
)

/** "HH:mm[:ss]" → 当日分钟数（时段均在同一天内，跨天场景后端模板不支持） */
function minutesOf(time: string): number {
  const [hour, minute] = time.split(':').map(Number)
  return hour * 60 + minute
}

/** 当前已选合并区间（连续不变式下即最早段 start / 最晚段 end） */
const mergedStart = computed(() =>
  selectedSlots.value.reduce<string | null>(
    (min, slot) => (min === null || slot.startTime < min ? slot.startTime : min),
    null
  )
)

const mergedEnd = computed(() =>
  selectedSlots.value.reduce<string | null>(
    (max, slot) => (max === null || slot.endTime > max ? slot.endTime : max),
    null
  )
)

const mergedMinutes = computed(() =>
  mergedStart.value && mergedEnd.value
    ? minutesOf(mergedEnd.value) - minutesOf(mergedStart.value)
    : 0
)

function isSelected(slot: IAvailableViewingTimeslot): boolean {
  return selectedSlots.value.some((item) => item.timeslotId === slot.timeslotId)
}

/** 移除可能把连续段拆成两截：保留最长连续段（并列取最早段），维持"选区恒连续"不变式 */
function trimToLongestRun(slots: IAvailableViewingTimeslot[]): IAvailableViewingTimeslot[] {
  const sorted = [...slots].sort((a, b) => a.startTime.localeCompare(b.startTime))
  let best: IAvailableViewingTimeslot[] = []
  let current: IAvailableViewingTimeslot[] = []
  for (const slot of sorted) {
    const prev = current[current.length - 1]
    if (prev && prev.endTime !== slot.startTime) {
      if (current.length > best.length) best = current
      current = []
    }
    current.push(slot)
  }
  if (current.length > best.length) best = current
  return best
}

/** 时段卡点选：已选再点=取消；新选必须与已选集合相邻连续，且合并时长不得超上限 */
function toggleSlot(slot: IAvailableViewingTimeslot): void {
  if (slotFull(slot)) return
  if (isSelected(slot)) {
    selectedSlots.value = trimToLongestRun(
      selectedSlots.value.filter((item) => item.timeslotId !== slot.timeslotId)
    )
    return
  }
  if (selectedSlots.value.length > 0) {
    const adjacent = selectedSlots.value.some(
      (item) => item.endTime === slot.startTime || item.startTime === slot.endTime
    )
    if (!adjacent) {
      ElMessage.warning('仅支持连续时段')
      return
    }
    const spanStart = mergedStart.value && slot.startTime < mergedStart.value ? slot.startTime : mergedStart.value ?? slot.startTime
    const spanEnd = mergedEnd.value && slot.endTime > mergedEnd.value ? slot.endTime : mergedEnd.value ?? slot.endTime
    if (minutesOf(spanEnd) - minutesOf(spanStart) > MAX_CONTINUOUS_MINUTES) {
      ElMessage.warning(`连续时段最长 ${MAX_CONTINUOUS_MINUTES} 分钟，请调整选择`)
      return
    }
  }
  selectedSlots.value = [...selectedSlots.value, slot]
}

/** 第 2 步显式推进（多选不能像单选那样选即跳转），校验时机对齐资源三步页的点击即校验 */
function goNextFromStep2(): void {
  if (selectedSlots.value.length === 0) {
    ElMessage.warning('请先选择至少一个可约时段')
    return
  }
  step.value = 3
}

/* ------------------------------ 第三步：确认提交 ------------------------------ */

const form = reactive({
  contactPhone: '',
  remark: ''
})

const submitting = ref(false)

const weekdayText = computed(() => {
  if (!selectedDate.value) return ''
  return WEEKDAYS[(new Date(`${selectedDate.value}T00:00:00`).getDay() + 6) % 7]
})

/** 联系电话可选；有填写时按后端手机号格式校验 */
const phoneValid = computed(() => !form.contactPhone.trim() || /^1[3-9]\d{9}$/.test(form.contactPhone.trim()))

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  if (!selectedDate.value || !mergedStart.value || !mergedEnd.value) {
    ElMessage.warning('请先选择看房日期与时段')
    return
  }
  if (!phoneValid.value) {
    ElMessage.warning('请填写正确的 11 位手机号')
    return
  }
  submitting.value = true
  try {
    const payload = {
      housingId,
      appointmentDate: selectedDate.value,
      startTime: mergedStart.value,
      endTime: mergedEnd.value,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined
    }
    const created = conversationId ? await createConversationViewing(conversationId,payload) : await createViewingAppointment(payload)
    ElMessage.success('看房预约已提交，等待管理员确认')
    /* 成功跳该预约详情页（带看会话所在）；响应异常缺 id 时回落看房 tab */
    onCreated(created?.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交看房预约失败')
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(async () => {
  if (invalidEntry) {
    onInvalid?.()
    return
  }
  try {
    housing.value = conversationId ? await getConversationViewingContext(conversationId) : await getHousingDetail(housingId)
    housingId=housing.value.id
    void loadMonthSlots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源信息失败')
  }
  try {
    profile.value = await getMyProfile()
    form.contactPhone = profile.value.phone
  } catch {
    /* 资料加载失败不阻塞预约流程，联系人与电话可手填 */
  }
})

return { housing, profile, STEPS, step, canGoto, gotoStep, WEEKDAYS, today, todayISO, viewYear, viewMonth, selectedDate, monthLoading, isCurrentMonth, calendarCells, dayState, monthHasOpen, shiftMonth, backToCurrentMonth, pickDate, MAX_CONTINUOUS_MINUTES, daySlots, dayLoading, selectedSlots, slotFull, slotLabel, allDaySlotsFull, mergedStart, mergedEnd, mergedMinutes, isSelected, toggleSlot, goNextFromStep2, form, submitting, weekdayText, handleSubmit }
}
