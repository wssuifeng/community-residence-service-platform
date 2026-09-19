<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/common/Pagination.vue'
import { listReservations, cancelReservation } from '@/api/reservation'
import type { IResourceReservation, ReservationStatus } from '@/types/modules/reservation'
import { reservationStatusLabels } from '@/types/modules/reservation'

/** 我的预约：左日历（标记有预约的日期、点选单日筛选）+ 右预约卡列表（默认未来 7 天口径，DEF-052）+ 底部规则提示 */

const query = reactive({
  page: 1,
  size: 10,
  status: '' as '' | ReservationStatus
})
const total = ref(0)
const records = ref<IResourceReservation[]>([])
const loading = ref(false)

/* ---------- 日历状态 ---------- */

const today = new Date()
const viewYear = ref(today.getFullYear())
const viewMonth = ref(today.getMonth())
const selectedDate = ref<string | null>(null)
/** 有预约的日期集合（YYYY-MM-DD）：取全量（≤100 条）预约提取，仅作标记 */
const markedDates = ref<Set<string>>(new Set())

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

function toISODate(year: number, month: number, day: number): string {
  return `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

const todayISO = toISODate(today.getFullYear(), today.getMonth(), today.getDate())

/** ISO 日期字符串加 N 天（用于「未来 7 天」口径上界） */
function plusDaysISO(iso: string, days: number): string {
  const d = new Date(`${iso}T00:00:00`)
  d.setDate(d.getDate() + days)
  return toISODate(d.getFullYear(), d.getMonth(), d.getDate())
}

/* ---------- 日期口径筛选（DEF-052）：默认未来 7 天，可按月/按区间，可清空看全部 ---------- */

type FilterMode = 'next7' | 'month' | 'range' | 'all'

const filterMode = ref<FilterMode>('next7')
/** 按月口径：YYYY-MM（el-date-picker value-format） */
const monthValue = ref<string>(todayISO.slice(0, 7))
/** 按区间口径：[起, 止]（进入该口径时预填未来 7 天，避免空参歧义） */
const rangeValue = ref<[string, string] | null>([todayISO, plusDaysISO(todayISO, 7)])

/**
 * 当前生效的日期查询参数：日历点选单日优先（单日起止相同）；
 * 「全部」口径与未选完的区间/月份不传日期参数（后端返回全部历史）
 */
const activeRange = computed<{ startDate?: string; endDate?: string }>(() => {
  if (selectedDate.value) {
    return { startDate: selectedDate.value, endDate: selectedDate.value }
  }
  switch (filterMode.value) {
    case 'next7':
      return { startDate: todayISO, endDate: plusDaysISO(todayISO, 7) }
    case 'month': {
      if (!monthValue.value) return {}
      const [year, month] = monthValue.value.split('-').map(Number)
      return {
        startDate: toISODate(year, month - 1, 1),
        endDate: toISODate(year, month - 1, new Date(year, month, 0).getDate())
      }
    }
    case 'range': {
      const [start, end] = rangeValue.value ?? []
      return start && end ? { startDate: start, endDate: end } : {}
    }
    default:
      return {}
  }
})

/** 列表头筛选口径提示 */
const filterHint = computed(() => {
  if (selectedDate.value) {
    return `已筛选：${selectedDate.value.slice(5).replace('-', '月')}日`
  }
  switch (filterMode.value) {
    case 'next7':
      return `口径：未来 7 天（${todayISO} ~ ${plusDaysISO(todayISO, 7)}）`
    case 'month':
      return monthValue.value ? `口径：${monthValue.value.replace('-', '年')}月` : '口径：按月'
    case 'range': {
      const [start, end] = rangeValue.value ?? []
      return start && end ? `口径：${start} ~ ${end}` : '口径：自定义区间'
    }
    default:
      return '展示全部历史预约'
  }
})

/** 切换口径：按月/按区间缺省值兜底后立即生效 */
function handleModeChange(): void {
  if (filterMode.value === 'month' && !monthValue.value) {
    monthValue.value = todayISO.slice(0, 7)
  }
  if (filterMode.value === 'range' && !rangeValue.value) {
    rangeValue.value = [todayISO, plusDaysISO(todayISO, 7)]
  }
  query.page = 1
  loadList()
}

/** 月/区间选择器变更（含清空）后立即生效 */
function handleRangeParamChange(): void {
  query.page = 1
  loadList()
}

/* 日历格子：周一开头；空格补齐首周（getDay 周日=0 → 周一开头的偏移） */
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

function shiftMonth(delta: number): void {
  const next = new Date(viewYear.value, viewMonth.value + delta, 1)
  viewYear.value = next.getFullYear()
  viewMonth.value = next.getMonth()
}

/* 日历点选单日为最高优先筛选（覆盖口径选择）；再点同一天或「全部」清除回到当前口径 */
function toggleDate(iso: string): void {
  selectedDate.value = selectedDate.value === iso ? null : iso
  query.page = 1
  loadList()
}

function clearDateFilter(): void {
  selectedDate.value = null
  query.page = 1
  loadList()
}

/* 日历标记：与列表查询独立，取全量预约日期（多于 100 条时只标记最近 100 条） */
async function loadMarkedDates(): Promise<void> {
  try {
    const result = await listReservations({ page: 1, size: 100 })
    markedDates.value = new Set(
      result.records.map((row) => row.reserveDate ?? row.reservationDate).filter(Boolean)
    )
  } catch {
    markedDates.value = new Set()
  }
}

/* ---------- 列表 ---------- */

const canCancel = (row: IResourceReservation): boolean =>
  row.status === 'PENDING' || row.status === 'CONFIRMED'

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listReservations({
      page: query.page,
      size: query.size,
      status: query.status === '' ? undefined : query.status,
      startDate: activeRange.value.startDate,
      endDate: activeRange.value.endDate
    })
    /* 按 id 去重兜底：防御后端异常返回重复行；正常数据 id 唯一，Set 过滤幂等不影响展示 */
    const seen = new Set<number>()
    records.value = result.records.filter((row) => {
      if (seen.has(row.id)) return false
      seen.add(row.id)
      return true
    })
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载预约列表失败')
  } finally {
    loading.value = false
  }
}

/** 时段文案：9月10日 18:00-19:00（startTime/endTime 可能带秒） */
function slotText(row: IResourceReservation): string {
  const date = row.reserveDate ?? row.reservationDate
  const parsed = new Date(date)
  const dateText = Number.isNaN(parsed.getTime())
    ? date
    : `${parsed.getMonth() + 1}月${parsed.getDate()}日`
  return `${dateText} ${row.startTime.slice(0, 5)}-${row.endTime.slice(0, 5)}`
}

/** 取消需填写原因（ReservationReasonDTO.reason 必填；后端无预约单号字段，标题用 #id） */
async function handleCancel(row: IResourceReservation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', `取消预约 #${row.id}`, {
      confirmButtonText: '确认取消预约',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：临时有事，无法按时使用',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写取消原因'),
      type: 'warning'
    })
    await cancelReservation(row.id, { reason: value.trim() })
    ElMessage.success('预约已取消')
    loadList()
    void loadMarkedDates()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消预约失败')
  }
}

const isEmpty = computed(() => !loading.value && records.value.length === 0)

onMounted(() => {
  loadList()
  void loadMarkedDates()
})
</script>

<template>
  <section class="reservation-list">
    <header class="page-head">
      <div>
        <h1>我的预约</h1>
        <p class="page-head-sub">社区公共资源预约记录，待审核与已预约的预约可取消</p>
      </div>
      <router-link to="/resident/resources">
        <el-button type="primary">＋ 预约公共资源</el-button>
      </router-link>
    </header>

    <div class="reservation-grid">
      <!-- 左：日历容器（周一开头，今天蓝圈，有预约的日期打绿点） -->
      <div class="calendar-card card">
        <div class="calendar-head">
          <span class="calendar-title">{{ viewYear }}年 {{ viewMonth + 1 }}月</span>
          <div class="calendar-nav">
            <button type="button" aria-label="上月" @click="shiftMonth(-1)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
            </button>
            <button type="button" aria-label="下月" @click="shiftMonth(1)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
            </button>
          </div>
        </div>

        <div class="calendar-weekdays">
          <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
        </div>

        <div class="calendar-cells">
          <button
            v-for="(cell, index) in calendarCells"
            :key="index"
            type="button"
            class="calendar-cell"
            :class="{
              'is-blank': cell === null,
              'is-today': cell?.iso === todayISO,
              'is-selected': cell !== null && cell.iso === selectedDate
            }"
            :disabled="cell === null"
            @click="cell && toggleDate(cell.iso)"
          >
            <template v-if="cell">
              <span class="cell-day">{{ cell.day }}</span>
              <span v-if="markedDates.has(cell.iso)" class="cell-dot" aria-hidden="true"></span>
            </template>
          </button>
        </div>

        <button
          v-if="selectedDate"
          type="button"
          class="calendar-clear"
          @click="clearDateFilter"
        >
          清除单日筛选（回到当前口径）
        </button>
      </div>

      <!-- 右：预约列表容器（同高 stretch，超高内部竖向滚动，分页条在容器底部） -->
      <div class="list-card card">
        <div class="list-card-head">
          <span class="list-filter-hint">{{ filterHint }}</span>
          <el-select
            v-model="query.status"
            class="list-status-select"
            placeholder="全部状态"
            clearable
            @change="query.page = 1; loadList()"
          >
            <el-option
              v-for="(label, value) in reservationStatusLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </div>

        <!-- 日期口径筛选（DEF-052）：默认未来 7 天；按月/按区间选择；全部=清空日期参数看历史 -->
        <div class="list-filter-bar">
          <el-radio-group v-model="filterMode" size="small" @change="handleModeChange">
            <el-radio-button value="next7">未来 7 天</el-radio-button>
            <el-radio-button value="month">按月</el-radio-button>
            <el-radio-button value="range">按日期</el-radio-button>
            <el-radio-button value="all">全部</el-radio-button>
          </el-radio-group>
          <el-date-picker
            v-if="filterMode === 'month'"
            v-model="monthValue"
            type="month"
            value-format="YYYY-MM"
            style="width: 200px"
            placeholder="选择月份"
            clearable
            @change="handleRangeParamChange"
          />
          <el-date-picker
            v-if="filterMode === 'range'"
            v-model="rangeValue"
            type="daterange"
            value-format="YYYY-MM-DD"
            style="width: 280px"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            clearable
            @change="handleRangeParamChange"
          />
        </div>

        <div v-loading="loading" class="list-scroll">
          <div v-if="isEmpty" class="empty-state">
            <p>{{ filterMode === 'all' && !selectedDate ? '还没有预约记录，去发起一个吧' : '所选范围内没有预约记录' }}</p>
          </div>

          <article v-for="row in records" v-else :key="row.id" class="reservation-card">
            <!-- 资源无图片字段：用资源类型 SVG 占位块 -->
            <span class="resource-thumb">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="18" rx="2" />
                <path d="M3 9h18M8 4v5" />
                <path d="M8 14h4M8 17h6" />
              </svg>
            </span>
            <div class="card-main">
              <h2 class="card-title">{{ row.resourceName }}</h2>
              <p class="card-slot">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7v5l3 3" />
                </svg>
                {{ slotText(row) }}
              </p>
              <span class="status-pill" :data-status="row.status">
                {{ reservationStatusLabels[row.status] }}
              </span>
            </div>
            <div class="card-actions">
              <el-button
                v-if="canCancel(row)"
                text
                type="primary"
                @click="handleCancel(row)"
              >
                取消预约
              </el-button>
            </div>
          </article>
        </div>

        <Pagination
          v-model:page="query.page"
          v-model:size="query.size"
          :total="total"
          @update:page="loadList"
          @update:size="loadList"
        />
      </div>
    </div>

    <!-- 底部规则提示条（sys_config 无规则文案字段，静态文案） -->
    <div class="rule-bar">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 8h.01M12 11v5" />
      </svg>
      <span>预约规则：每人每天限约 2 个时段，违约 3 次将暂停预约资格。</span>
    </div>
  </section>
</template>

<style scoped>
.reservation-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
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

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

/* 左日历 + 右列表：stretch 同高 */
.reservation-grid {
  display: grid;
  grid-template-columns: 340px 1fr;
  gap: var(--spacing-md);
  align-items: stretch;
}

/* 日历 */
.calendar-card {
  display: flex;
  flex-direction: column;
}

.calendar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.calendar-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.calendar-nav {
  display: flex;
  gap: var(--spacing-xs);
}

.calendar-nav button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
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

.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  margin-bottom: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: center;
}

.calendar-cells {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  row-gap: var(--spacing-xs);
}

.calendar-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--spacing-xs) 0 5px;
  border: none;
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
}

.calendar-cell.is-blank {
  cursor: default;
}

.cell-day {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
}

/* 今天：蓝色描边圈；选中：蓝色实心圆 */
.calendar-cell.is-today .cell-day {
  border: 1.5px solid var(--color-primary);
  color: var(--color-primary);
}

.calendar-cell.is-selected .cell-day {
  background: var(--color-primary);
  border: 1.5px solid var(--color-primary);
  color: #fff;
}

.calendar-cell:not(.is-blank):hover .cell-day {
  background: var(--color-primary-bg);
}

.calendar-cell.is-selected:hover .cell-day {
  background: var(--color-primary);
}

/* 有预约的日期：绿点 */
.cell-dot {
  width: 5px;
  height: 5px;
  border-radius: var(--radius-circle);
  background: var(--color-success);
}

.calendar-clear {
  margin-top: var(--spacing-md);
  align-self: center;
  border: none;
  background: none;
  font-size: var(--font-size-xs);
  color: var(--color-primary);
  cursor: pointer;
}

/* 右列表容器：内部竖向滚动（hover 显示滚动条），分页条沉底 */
.list-card {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.list-card-head {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.list-filter-hint {
  margin-right: auto;
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

/* 口径筛选栏：快捷口径 + 条件式日期选择器同行排布（选择器宽度用内联 style 控制） */
.list-filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.list-status-select {
  width: 130px;
}

.list-scroll {
  flex: 1;
  min-height: 320px;
  max-height: 520px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  padding-right: var(--spacing-xs);
}

.list-scroll:hover {
  scrollbar-color: var(--color-text-disabled) transparent;
}

.list-scroll::-webkit-scrollbar {
  width: 4px;
}

.list-scroll::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.list-scroll:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.empty-state {
  margin: auto;
  text-align: center;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  padding: var(--spacing-xxl) 0;
}

/* 预约卡：左资源占位块 / 中内容 / 右取消 */
.reservation-card {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  transition: box-shadow 0.2s ease;
}

.reservation-card:hover {
  box-shadow: var(--shadow-md);
}

.resource-thumb {
  flex-shrink: 0;
  width: 72px;
  height: 72px;
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.resource-thumb svg {
  width: 30px;
  height: 30px;
}

.card-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.card-slot {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.card-slot svg {
  width: 14px;
  height: 14px;
}

/* 状态胶囊：已预约绿 / 待审核黄 / 其余灰（已拒绝、已违约用红色系） */
.status-pill {
  align-self: flex-start;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.status-pill[data-status='CONFIRMED'] {
  background: rgba(16, 185, 129, 0.1);
  color: var(--color-success);
}

.status-pill[data-status='PENDING'] {
  background: rgba(245, 158, 11, 0.12);
  color: var(--color-warning);
}

.status-pill[data-status='COMPLETED'],
.status-pill[data-status='CANCELLED'] {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.status-pill[data-status='REJECTED'],
.status-pill[data-status='VIOLATED'] {
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.card-actions {
  flex-shrink: 0;
}

/* 底部规则提示条 */
.rule-bar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-sm);
}

.rule-bar svg {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

/* 响应式：窄屏降单栏 */
@media (max-width: 991px) {
  .reservation-grid {
    grid-template-columns: 1fr;
  }
}
</style>
