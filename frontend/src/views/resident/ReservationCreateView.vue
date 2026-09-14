<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getResource } from '@/api/community'
import { listAvailableTimeslots, createReservation } from '@/api/reservation'
import { getMyProfile } from '@/api/resident'
import type { IPublicResource } from '@/types/modules/community'
import type { IAvailableTimeslot } from '@/types/modules/reservation'
import type { IResident } from '@/types/modules/resident'

/**
 * 预约子页（居民端三段预约流第三段，大表单治理示范）：
 * 必带 ?resourceId= 进入；左侧步骤轨（选日期 → 选时段 → 确认）+ 右侧单步内容。
 * 第一步自写月历（过去/无开放时段/约满日期禁选），第二步下拉选当日开放时段（含余量），
 * 第三步摘要卡确认提交。提交中禁点防重，成功后跳「我的预约」。
 */

const route = useRoute()
const router = useRouter()

const resourceId = Number(route.query.resourceId)
/** 无 resourceId 直达（旧书签/直达链）→ 回资源列表 */
const invalidEntry = !Number.isInteger(resourceId) || resourceId <= 0

const resource = ref<IPublicResource | null>(null)
const profile = ref<IResident | null>(null)

/* ------------------------------ 步骤轨 ------------------------------ */

const STEPS = [
  { step: 1, title: '选择日期', desc: '在日历上选择预约日期' },
  { step: 2, title: '选择时段', desc: '挑选该日期的开放时段' },
  { step: 3, title: '确认提交', desc: '核对信息并提交预约' }
] as const

const step = ref(1)

/** 步骤回跳仅限已解锁步骤（第 2 步需已选日期，第 3 步需已选时段） */
function canGoto(target: number): boolean {
  if (target === 1) return true
  if (target === 2) return selectedDate.value !== null
  return selectedTimeslotId.value !== null
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
const monthSlots = ref<IAvailableTimeslot[]>([])
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
  const map = new Map<string, IAvailableTimeslot[]>()
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
  return list.some((slot) => slot.currentBookings < slot.maxBookings) ? 'open' : 'full'
}

function shiftMonth(delta: number): void {
  const next = new Date(viewYear.value, viewMonth.value + delta, 1)
  viewYear.value = next.getFullYear()
  viewMonth.value = next.getMonth()
}

function backToCurrentMonth(): void {
  viewYear.value = today.getFullYear()
  viewMonth.value = today.getMonth()
}

/** 月份切换重查当月时段；序号令牌防慢响应回填旧月数据 */
let monthSeq = 0
async function loadMonthSlots(): Promise<void> {
  const seq = monthSeq + 1
  monthSeq = seq
  const first = toISODate(viewYear.value, viewMonth.value, 1)
  const last = toISODate(viewYear.value, viewMonth.value, new Date(viewYear.value, viewMonth.value + 1, 0).getDate())
  monthLoading.value = true
  try {
    const data = await listAvailableTimeslots(resourceId, { startDate: first, endDate: last })
    if (seq === monthSeq) monthSlots.value = data
  } catch (error) {
    if (seq === monthSeq) {
      monthSlots.value = []
      ElMessage.error(error instanceof Error ? error.message : '加载开放时段失败')
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
  selectedTimeslotId.value = null
  selectedSlot.value = null
  void openStep2()
}

/* ------------------------------ 第二步：下拉选时段 ------------------------------ */

const daySlots = ref<IAvailableTimeslot[]>([])
const dayLoading = ref(false)
const selectedTimeslotId = ref<number | null>(null)
const selectedSlot = ref<IAvailableTimeslot | null>(null)

let daySeq = 0
async function openStep2(): Promise<void> {
  if (!selectedDate.value) return
  step.value = 2
  const seq = daySeq + 1
  daySeq = seq
  dayLoading.value = true
  try {
    const data = await listAvailableTimeslots(resourceId, {
      startDate: selectedDate.value,
      endDate: selectedDate.value
    })
    if (seq === daySeq) {
      /* 后端模板返回顺序不保证，按开始时间排序保证下拉展示稳定 */
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

function slotFull(slot: IAvailableTimeslot): boolean {
  return slot.status === 'FULL' || slot.currentBookings >= slot.maxBookings
}

function slotLabel(slot: IAvailableTimeslot): string {
  return `${slot.startTime.slice(0, 5)} ~ ${slot.endTime.slice(0, 5)}`
}

const allDaySlotsFull = computed(
  () => daySlots.value.length > 0 && daySlots.value.every((slot) => slotFull(slot))
)

/** 选中时段 → 进入第三步确认 */
function pickSlot(timeslotId: number): void {
  const slot = daySlots.value.find((item) => item.timeslotId === timeslotId) ?? null
  if (!slot || slotFull(slot)) return
  selectedTimeslotId.value = timeslotId
  selectedSlot.value = slot
  step.value = 3
}

/* ------------------------------ 第三步：确认提交 ------------------------------ */

const form = reactive({
  contactPhone: '',
  purpose: '',
  remark: ''
})

const submitting = ref(false)

const weekdayText = computed(() => {
  if (!selectedDate.value) return ''
  return WEEKDAYS[(new Date(`${selectedDate.value}T00:00:00`).getDay() + 6) % 7]
})

/** 联系电话选填，填则须满足后端手机号格式（避免 400 才发现） */
const phoneValid = computed(
  () => form.contactPhone.trim() === '' || /^1[3-9]\d{9}$/.test(form.contactPhone.trim())
)

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  if (!selectedDate.value || !selectedSlot.value) {
    ElMessage.warning('请先选择预约日期与时段')
    return
  }
  if (!phoneValid.value) {
    ElMessage.warning('联系电话格式不正确（11 位手机号）')
    return
  }
  submitting.value = true
  try {
    await createReservation({
      resourceId,
      reserveDate: selectedDate.value,
      startTime: selectedSlot.value.startTime,
      endTime: selectedSlot.value.endTime,
      purpose: form.purpose.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined
    })
    ElMessage.success('预约已提交，等待管理员审核')
    router.push('/resident/reservations')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交预约失败')
    submitting.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(async () => {
  if (invalidEntry) {
    router.replace('/resident/resources')
    return
  }
  void loadMonthSlots()
  try {
    resource.value = await getResource(resourceId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载资源信息失败')
  }
  try {
    profile.value = await getMyProfile()
    form.contactPhone = profile.value.phone
  } catch {
    /* 资料加载失败不阻塞预约流程，联系电话可手填 */
  }
})
</script>

<template>
  <section class="reservation-create">
    <nav class="breadcrumb">
      <router-link to="/resident/resources">公共资源</router-link>
      <template v-if="resource">
        <span class="breadcrumb-sep">/</span>
        <router-link :to="`/resident/resources/${resource.id}`">{{ resource.name }}</router-link>
      </template>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">发起预约</span>
    </nav>

    <header class="page-head">
      <h1>发起预约</h1>
      <p class="page-head-sub">
        <template v-if="resource">{{ resource.communityName }} · {{ resource.name }}</template>
        <template v-else>正在加载资源信息…</template>
      </p>
    </header>

    <div class="create-layout">
      <!-- 左：步骤轨（已完成可点回跳，未解锁置灰） -->
      <aside class="step-rail">
        <button
          v-for="item in STEPS"
          :key="item.step"
          type="button"
          class="step-item"
          :class="{
            'is-current': step === item.step,
            'is-done': step > item.step,
            'is-locked': !canGoto(item.step) && step < item.step
          }"
          :disabled="!canGoto(item.step)"
          @click="gotoStep(item.step)"
        >
          <span class="step-index">
            <svg v-if="step > item.step" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 6 9 17l-5-5" />
            </svg>
            <template v-else>{{ item.step }}</template>
          </span>
          <span class="step-text">
            <span class="step-title">{{ item.title }}</span>
            <span class="step-desc">{{ item.desc }}</span>
          </span>
        </button>
      </aside>

      <!-- 右：单步内容卡 -->
      <div class="step-panel">
        <!-- 第一步：月历 -->
        <div v-show="step === 1" class="panel-body">
          <div class="calendar-head">
            <span class="calendar-title">{{ viewYear }}年 {{ viewMonth + 1 }}月</span>
            <div class="calendar-nav">
              <button type="button" aria-label="上月" @click="shiftMonth(-1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
              </button>
              <button v-if="!isCurrentMonth" type="button" class="calendar-back" @click="backToCurrentMonth">
                回本月
              </button>
              <button type="button" aria-label="下月" @click="shiftMonth(1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
              </button>
            </div>
          </div>

          <div class="calendar-weekdays">
            <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
          </div>

          <div v-loading="monthLoading" class="calendar-cells">
            <button
              v-for="(cell, index) in calendarCells"
              :key="index"
              type="button"
              class="calendar-cell"
              :class="{
                'is-blank': cell === null,
                'is-today': cell?.iso === todayISO,
                'is-selected': cell !== null && cell.iso === selectedDate,
                'is-off': cell !== null && dayState(cell.iso) !== 'open'
              }"
              :disabled="cell === null || dayState(cell.iso) !== 'open'"
              @click="cell && pickDate(cell.iso)"
            >
              <template v-if="cell">
                <span class="cell-day">{{ cell.day }}</span>
                <span v-if="dayState(cell.iso) === 'open'" class="cell-dot" aria-hidden="true"></span>
                <span v-else-if="dayState(cell.iso) === 'full'" class="cell-full">满</span>
              </template>
            </button>
          </div>

          <p class="calendar-legend">
            <span class="legend-item"><span class="legend-dot"></span>有可约时段</span>
            <span class="legend-item"><span class="legend-full">满</span>当日时段已约满</span>
            <span class="legend-item"><span class="legend-off"></span>无开放时段 / 已过期</span>
          </p>
        </div>

        <!-- 第二步：时段下拉 -->
        <div v-show="step === 2" class="panel-body">
          <h2 class="panel-title">选择 {{ selectedDate }}（{{ weekdayText }}）的开放时段</h2>
          <div v-loading="dayLoading" class="slot-picker">
            <el-select
              :model-value="selectedTimeslotId"
              placeholder="下拉选择开放时段"
              class="slot-select"
              @update:model-value="pickSlot"
            >
              <el-option
                v-for="slot in daySlots"
                :key="slot.timeslotId"
                :value="slot.timeslotId"
                :label="slotLabel(slot)"
                :disabled="slotFull(slot)"
              >
                <span class="slot-option">
                  <span class="slot-option-time">{{ slotLabel(slot) }}</span>
                  <span class="slot-option-quota" :class="{ 'is-full': slotFull(slot) }">
                    {{ slotFull(slot) ? '已约满' : `余 ${slot.maxBookings - slot.currentBookings}` }}
                  </span>
                </span>
              </el-option>
            </el-select>
            <p v-if="!dayLoading && daySlots.length === 0" class="slot-hint">当日暂无开放时段</p>
            <p v-else-if="allDaySlotsFull" class="slot-hint">当日时段已全部约满，请换一天或稍后再试</p>
            <button type="button" class="ghost-btn" @click="gotoStep(1)">重新选日期</button>
          </div>
        </div>

        <!-- 第三步：摘要确认 -->
        <div v-show="step === 3" class="panel-body">
          <h2 class="panel-title">确认预约信息</h2>
          <dl class="summary">
            <div class="summary-row">
              <dt>预约资源</dt>
              <dd>{{ resource ? `${resource.communityName} · ${resource.name}` : '—' }}</dd>
            </div>
            <div class="summary-row">
              <dt>预约日期</dt>
              <dd>{{ selectedDate }} {{ selectedDate ? `（${weekdayText}）` : '' }}</dd>
            </div>
            <div class="summary-row">
              <dt>预约时段</dt>
              <dd>{{ selectedSlot ? slotLabel(selectedSlot) : '—' }}</dd>
            </div>
            <div class="summary-row">
              <dt>预约人</dt>
              <dd>{{ profile?.realName || '当前登录居民' }}</dd>
            </div>
          </dl>

          <div class="supplement">
            <el-form label-width="76px" class="supplement-form" @submit.prevent>
              <el-form-item label="联系电话">
                <el-input
                  v-model="form.contactPhone"
                  placeholder="用于预约确认联系（选填）"
                  maxlength="11"
                  style="width: 240px"
                />
              </el-form-item>
              <el-form-item label="使用用途">
                <el-input
                  v-model="form.purpose"
                  type="textarea"
                  :rows="2"
                  placeholder="简单说明用途，帮助管理员审核（选填）"
                  maxlength="200"
                  show-word-limit
                />
              </el-form-item>
              <el-form-item label="备注">
                <el-input
                  v-model="form.remark"
                  type="textarea"
                  :rows="2"
                  placeholder="其他需要说明的事项（选填）"
                  maxlength="500"
                  show-word-limit
                />
              </el-form-item>
            </el-form>
          </div>

          <div class="submit-row">
            <el-button
              type="primary"
              round
              size="large"
              :loading="submitting"
              :disabled="submitting"
              @click="handleSubmit"
            >
              {{ submitting ? '提交中…' : '提交预约' }}
            </el-button>
            <el-button round size="large" :disabled="submitting" @click="gotoStep(2)">
              上一步
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.reservation-create {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.breadcrumb a {
  color: var(--color-text-secondary);
}

.breadcrumb a:hover {
  color: var(--color-primary);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 左步骤轨 + 右内容卡 */
.create-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: var(--spacing-md);
  align-items: start;
}

.step-rail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  position: sticky;
  top: var(--spacing-md);
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  text-align: left;
  cursor: pointer;
}

.step-item:disabled {
  cursor: not-allowed;
}

.step-item.is-current {
  background: var(--color-primary-bg);
}

.step-index {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-circle);
  border: 1.5px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.step-item.is-current .step-index {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.step-item.is-done .step-index {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.step-item.is-done .step-index svg {
  width: 13px;
  height: 13px;
}

.step-item.is-locked .step-index {
  opacity: 0.55;
}

.step-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.step-item.is-locked .step-title {
  color: var(--color-text-disabled);
}

.step-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.step-panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
  min-height: 380px;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.panel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

/* 月历：与「我的预约」日历同语言 */
.calendar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.calendar-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.calendar-nav {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.calendar-nav button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 26px;
  min-width: 26px;
  padding: 0 var(--spacing-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.calendar-nav button:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.calendar-nav svg {
  width: 14px;
  height: 14px;
}

.calendar-back {
  font-size: var(--font-size-xs);
}

.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: center;
}

.calendar-cells {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  row-gap: var(--spacing-xs);
  min-height: 216px;
}

.calendar-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: var(--spacing-xs) 0;
  border: none;
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  min-height: 44px;
}

.calendar-cell.is-blank {
  cursor: default;
}

/* 禁选：过去/无时段/约满 置灰 */
.calendar-cell.is-off .cell-day {
  color: var(--color-text-disabled);
  opacity: 0.55;
}

.calendar-cell.is-off {
  cursor: not-allowed;
}

.cell-day {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
}

.calendar-cell.is-today .cell-day {
  border: 1.5px solid var(--color-primary);
  color: var(--color-primary);
}

.calendar-cell.is-selected .cell-day {
  background: var(--color-primary);
  border: 1.5px solid var(--color-primary);
  color: #fff;
}

.calendar-cell:not(.is-blank):not(.is-off):hover .cell-day {
  background: var(--color-primary-bg);
}

.calendar-cell.is-selected:hover .cell-day {
  background: var(--color-primary);
}

/* 有可约时段的日期：主色小点 */
.cell-dot {
  width: 5px;
  height: 5px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

/* 约满日期：灰色「满」角标 */
.cell-full {
  font-size: 10px;
  line-height: 1;
  color: var(--color-text-disabled);
}

.calendar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.legend-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

.legend-off {
  width: 12px;
  height: 12px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-hover);
}

.legend-full {
  font-size: 10px;
  color: var(--color-text-disabled);
}

/* 第二步：时段下拉 */
.slot-picker {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 160px;
}

.slot-select {
  max-width: 420px;
}

.slot-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.slot-option-time {
  font-family: var(--font-family-mono);
}

.slot-option-quota {
  font-size: var(--font-size-xs);
  color: var(--color-success);
}

.slot-option-quota.is-full {
  color: var(--color-text-disabled);
}

.slot-hint {
  margin: 0;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.ghost-btn {
  align-self: flex-start;
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.ghost-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

/* 第三步：摘要 + 补充信息 */
.summary {
  margin: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.summary-row {
  display: flex;
  align-items: baseline;
}

.summary-row + .summary-row {
  border-top: 1px dashed var(--color-border);
}

.summary-row dt {
  flex-shrink: 0;
  width: 96px;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.summary-row dd {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  font-family: var(--font-family-mono);
}

.supplement {
  border-top: 1px dashed var(--color-border);
  padding-top: var(--spacing-md);
}

.supplement-form {
  max-width: 560px;
}

.submit-row {
  display: flex;
  gap: var(--spacing-sm);
}

@media (max-width: 900px) {
  .create-layout {
    grid-template-columns: 1fr;
  }

  .step-rail {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .step-item {
    flex: 1;
    min-width: 140px;
  }
}
</style>
