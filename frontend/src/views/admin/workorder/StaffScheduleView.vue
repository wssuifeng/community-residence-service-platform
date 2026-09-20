<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import { getCommunityList } from '@/api/community'
import { listStaffCapabilities } from '@/api/staff'
import { batchSaveStaffSchedules, clearStaffSchedules, deleteStaffSchedule, listStaffSchedules } from '@/api/staff'
import type { ICommunity } from '@/types/modules/community'
import type { IStaffCapability, IStaffSchedule, ShiftType } from '@/types/modules/staff'
import { SHIFT_TYPE_OPTIONS, shiftTypeColors, shiftTypeDefaults, shiftTypeLabels } from '@/types/modules/staff'
import { useUserStore } from '@/store/user'

/**
 * 人员排班（V19，C4 物业自身排班管理）：
 * 以「服务人员 × 一周七天」的网格编辑班次，是工单派单候选「今日班次」标签的数据来源。
 * 排班一人一社区一天一条，批量保存为覆盖式（同人同社区同日再存即更新），因此本页所有写操作
 * 只需带上 社区 + 人员 + 日期 + 班次，无需前端拼装差异集。
 */

const userStore = useUserStore()

const communityId = ref<number | null>(null)
const communities = ref<ICommunity[]>([])
const staffRows = ref<IStaffCapability[]>([])
const schedules = ref<IStaffSchedule[]>([])
const loading = ref(false)

/** 周区间：以周一为起点，避免周日归入下一周造成排班错位 */
const weekStart = ref<Date>(startOfWeek(new Date()))

const WEEK_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']

/** 本地日期串（YYYY-MM-DD）；不能用 toISOString，会被时区推成前一天 */
function toDateStr(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function startOfWeek(date: Date): Date {
  const base = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  /* getDay() 周日为 0，映射到周一为起点的偏移 */
  const offset = (base.getDay() + 6) % 7
  base.setDate(base.getDate() - offset)
  return base
}

function addDays(date: Date, days: number): Date {
  const next = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  next.setDate(next.getDate() + days)
  return next
}

const todayStr = toDateStr(new Date())

const weekDays = computed(() =>
  WEEK_LABELS.map((label, index) => {
    const date = addDays(weekStart.value, index)
    return {
      label,
      date: toDateStr(date),
      /** 表头副文案：09-21 形式，跨月也读得清 */
      shortDate: `${`${date.getMonth() + 1}`.padStart(2, '0')}-${`${date.getDate()}`.padStart(2, '0')}`,
      isToday: toDateStr(date) === todayStr
    }
  })
)

const weekRange = computed(() => {
  const days = weekDays.value
  return `${days[0].date} ~ ${days[6].date}`
})

const isCurrentWeek = computed(() => toDateStr(weekStart.value) === toDateStr(startOfWeek(new Date())))

/** 排班索引：staffId + 日期 → 记录（一人一社区一天一条，可直接当 key） */
const scheduleIndex = computed(() => {
  const map = new Map<string, IStaffSchedule>()
  for (const item of schedules.value) {
    map.set(`${item.staffId}|${item.workDate}`, item)
  }
  return map
})

const scheduleCount = computed(() => schedules.value.length)

function shiftColor(shiftType: ShiftType) {
  return shiftTypeColors[shiftType]
}

/** 单元格班次配色（未排班回落休息档灰，模板里无需判空） */
function cellShiftColor(cell: IScheduleCell) {
  return cell.schedule ? shiftTypeColors[cell.schedule.shiftType] : shiftTypeColors.REST
}

/** 时间区间文案（REST 无起止时间，显示为空由调用处决定） */
function timeRange(schedule: IStaffSchedule): string {
  if (schedule.shiftType === 'REST') return ''
  const start = schedule.startTime ? schedule.startTime.slice(0, 5) : ''
  const end = schedule.endTime ? schedule.endTime.slice(0, 5) : ''
  if (!start && !end) return ''
  return `${start}-${end}`
}

/** 网格单元格（人员 × 日期展开一次，避免模板里反复查索引与判空） */
interface IScheduleCell {
  date: string
  weekLabel: string
  isToday: boolean
  schedule: IStaffSchedule | null
  timeText: string
}

interface IGridRow {
  staff: IStaffCapability
  cells: IScheduleCell[]
}

const gridRows = computed<IGridRow[]>(() =>  staffRows.value.map((staff) => ({
    staff,
    cells: weekDays.value.map((day) => {
      const schedule = scheduleIndex.value.get(`${staff.staffId}|${day.date}`) ?? null
      return {
        date: day.date,
        weekLabel: day.label,
        isToday: day.isToday,
        schedule,
        timeText: schedule ? timeRange(schedule) : ''
      }
    })
  }))
)

/* ---------------- 数据加载 ---------------- */

async function loadCommunities(): Promise<void> {
  try {
    const page = await getCommunityList({ page: 1, size: 100 })
    communities.value = page.records
    const bound = userStore.user?.boundCommunities ?? []
    /* 管理员只管绑定社区：优先取绑定社区，超管回落全量列表首项 */
    if (bound.length > 0) {
      communityId.value = bound[0]
    } else if (page.records.length > 0) {
      communityId.value = page.records[0].id
    }
    if (communityId.value === null) {
      ElMessage.warning('暂无可管理的社区，请先创建社区或绑定社区')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '社区列表加载失败')
  }
}

async function loadWeek(): Promise<void> {
  if (!communityId.value) {
    staffRows.value = []
    schedules.value = []
    return
  }
  loading.value = true
  try {
    const [capabilityPage, scheduleList] = await Promise.all([
      listStaffCapabilities({ communityId: communityId.value, page: 1, size: 200 }),
      listStaffSchedules({
        communityId: communityId.value,
        startDate: weekDays.value[0].date,
        endDate: weekDays.value[6].date
      })
    ])
    staffRows.value = capabilityPage.records
    schedules.value = scheduleList
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '排班数据加载失败')
  } finally {
    loading.value = false
  }
}

function shiftWeek(offset: number): void {
  weekStart.value = addDays(weekStart.value, offset * 7)
  void loadWeek()
}

function goCurrentWeek(): void {
  weekStart.value = startOfWeek(new Date())
  void loadWeek()
}

/* ---------------- 单元格编辑（单人行单日） ---------------- */

const cellDialogVisible = ref(false)
const cellTarget = ref<{ staffId: number; staffName: string; date: string; weekLabel: string } | null>(null)
const cellForm = ref<{ shiftType: ShiftType; startTime: string | null; endTime: string | null; remark: string }>({
  shiftType: 'FULL',
  startTime: shiftTypeDefaults.FULL.startTime,
  endTime: shiftTypeDefaults.FULL.endTime,
  remark: ''
})
const saving = ref(false)

const cellSchedule = computed(() => {
  if (!cellTarget.value) return null
  return scheduleIndex.value.get(`${cellTarget.value.staffId}|${cellTarget.value.date}`) ?? null
})

function currentCell() {
  if (!cellTarget.value) return null
  const staff = staffRows.value.find((item) => item.staffId === cellTarget.value?.staffId)
  return staff ?? null
}

function openCellDialog(staff: IStaffCapability, date: string): void {
  const existing = scheduleIndex.value.get(`${staff.staffId}|${date}`) ?? null
  const weekIndex = weekDays.value.findIndex((day) => day.date === date)
  cellTarget.value = {
    staffId: staff.staffId,
    staffName: staff.realName,
    date,
    weekLabel: weekIndex >= 0 ? weekDays.value[weekIndex].label : ''
  }
  if (existing) {
    cellForm.value = {
      shiftType: existing.shiftType,
      startTime: existing.startTime ? existing.startTime.slice(0, 5) : null,
      endTime: existing.endTime ? existing.endTime.slice(0, 5) : null,
      remark: existing.remark ?? ''
    }
  } else {
    cellForm.value = {
      shiftType: 'FULL',
      startTime: shiftTypeDefaults.FULL.startTime,
      endTime: shiftTypeDefaults.FULL.endTime,
      remark: ''
    }
  }
  cellDialogVisible.value = true
}

/** 切换班次时用默认时间回填；休息日强制清空起止时间，避免存下无意义的时段 */
function handleShiftChange(shiftType: ShiftType): void {
  const preset = shiftTypeDefaults[shiftType]
  cellForm.value.startTime = preset.startTime
  cellForm.value.endTime = preset.endTime
}

async function saveCell(): Promise<void> {
  if (!communityId.value || !cellTarget.value) return
  const isRest = cellForm.value.shiftType === 'REST'
  saving.value = true
  try {
    await batchSaveStaffSchedules({
      communityId: communityId.value,
      staffIds: [cellTarget.value.staffId],
      dates: [cellTarget.value.date],
      shiftType: cellForm.value.shiftType,
      startTime: isRest ? undefined : cellForm.value.startTime ?? undefined,
      endTime: isRest ? undefined : cellForm.value.endTime ?? undefined,
      remark: cellForm.value.remark.trim() || undefined
    })
    ElMessage.success(`${cellTarget.value.staffName} ${cellTarget.value.date} 已排为${shiftTypeLabels[cellForm.value.shiftType]}`)
    cellDialogVisible.value = false
    await loadWeek()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '排班保存失败')
  } finally {
    saving.value = false
  }
}

/** 单元格小面板里清空当前人当前日（面板由 cellTarget 承载上下文） */
function removeCurrentCell(): void {
  const target = cellTarget.value
  const staff = currentCell()
  if (!target || !staff) return
  void removeCell(staff, target.date)
}

/** 清空单人单日：已存在排班才有记录可删，未排班直接提示 */
async function removeCell(staff: IStaffCapability, date: string): Promise<void> {
  const existing = scheduleIndex.value.get(`${staff.staffId}|${date}`)
  if (!existing) {
    ElMessage.info(`${staff.realName} ${date} 未排班`)
    return
  }
  try {
    await ElMessageBox.confirm(`确认清空 ${staff.realName} ${date} 的排班吗？`, '清空排班', {
      confirmButtonText: '确认清空',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await deleteStaffSchedule(existing.id)
    ElMessage.success('已清空该日排班')
    cellDialogVisible.value = false
    await loadWeek()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空失败')
  }
}

/* ---------------- 批量设班（多人 × 多日期） ---------------- */

const batchDialogVisible = ref(false)
const batchForm = ref<{
  staffIds: number[]
  dates: string[]
  shiftType: ShiftType
  startTime: string | null
  endTime: string | null
  remark: string
}>({
  staffIds: [],
  dates: [],
  shiftType: 'FULL',
  startTime: shiftTypeDefaults.FULL.startTime,
  endTime: shiftTypeDefaults.FULL.endTime,
  remark: ''
})

function openBatchDialog(): void {
  batchForm.value = {
    staffIds: staffRows.value.map((item) => item.staffId),
    dates: weekDays.value.map((day) => day.date),
    shiftType: 'FULL',
    startTime: shiftTypeDefaults.FULL.startTime,
    endTime: shiftTypeDefaults.FULL.endTime,
    remark: ''
  }
  batchDialogVisible.value = true
}

function handleBatchShiftChange(shiftType: ShiftType): void {
  const preset = shiftTypeDefaults[shiftType]
  batchForm.value.startTime = preset.startTime
  batchForm.value.endTime = preset.endTime
}

function selectWholeWeek(): void {
  batchForm.value.dates = weekDays.value.map((day) => day.date)
}

async function saveBatch(): Promise<void> {
  if (!communityId.value) return
  if (batchForm.value.staffIds.length === 0) {
    ElMessage.warning('请先选择服务人员')
    return
  }
  if (batchForm.value.dates.length === 0) {
    ElMessage.warning('请先选择排班日期')
    return
  }
  const isRest = batchForm.value.shiftType === 'REST'
  saving.value = true
  try {
    const result = await batchSaveStaffSchedules({
      communityId: communityId.value,
      staffIds: batchForm.value.staffIds,
      dates: batchForm.value.dates,
      shiftType: batchForm.value.shiftType,
      startTime: isRest ? undefined : batchForm.value.startTime ?? undefined,
      endTime: isRest ? undefined : batchForm.value.endTime ?? undefined,
      remark: batchForm.value.remark.trim() || undefined
    })
    ElMessage.success(
      `已为 ${batchForm.value.staffIds.length} 人 × ${batchForm.value.dates.length} 天设置${shiftTypeLabels[batchForm.value.shiftType]}，共保存 ${result.saved ?? 0} 条`
    )
    batchDialogVisible.value = false
    await loadWeek()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量设班失败')
  } finally {
    saving.value = false
  }
}

/** 清空本周：不带 staffIds 即该社区全部人员（后端语义），用于重排整周 */
async function clearWeek(): Promise<void> {
  if (!communityId.value) return
  try {
    await ElMessageBox.confirm(
      `确认清空 ${weekRange.value} 该社区全部服务人员的排班吗？清空后工单派单候选将显示「未排班」。`,
      '清空本周排班',
      { confirmButtonText: '确认清空', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const result = await clearStaffSchedules({
      communityId: communityId.value,
      startDate: weekDays.value[0].date,
      endDate: weekDays.value[6].date
    })
    ElMessage.success(`已清空本周排班 ${result.saved ?? 0} 条`)
    await loadWeek()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '清空失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  await loadCommunities()
  await loadWeek()
})
</script>

<template>
  <section class="staff-schedule">
    <AdminPageHeader title="人员排班" :subtitle="`每周排班决定派单候选上的「今日班次」标签：${weekRange}`">
      <el-button type="primary" :disabled="!communityId || staffRows.length === 0" @click="openBatchDialog">
        批量设班
      </el-button>
      <el-button :disabled="!communityId" :loading="saving" @click="clearWeek">清空本周</el-button>
    </AdminPageHeader>

    <div class="toolbar">
      <el-select
        v-model="communityId"
        placeholder="选择社区"
        filterable
        class="community-select"
        @change="loadWeek"
      >
        <el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>

      <div class="week-nav">
        <el-button @click="shiftWeek(-1)">上一周</el-button>
        <el-button :type="isCurrentWeek ? 'primary' : 'default'" plain @click="goCurrentWeek">本周</el-button>
        <el-button @click="shiftWeek(1)">下一周</el-button>
      </div>

      <span class="week-label">{{ weekRange }}</span>

      <div class="toolbar-right">
        <span class="summary">本周已排 {{ scheduleCount }} 条 · {{ staffRows.length }} 名服务人员</span>
        <el-button :loading="loading" @click="loadWeek">刷新</el-button>
      </div>
    </div>

    <ul class="legend">
      <li v-for="shift in SHIFT_TYPE_OPTIONS" :key="shift" class="legend-item">
        <span class="legend-chip" :style="{ backgroundColor: shiftColor(shift).bg, color: shiftColor(shift).fg }">
          {{ shiftTypeLabels[shift] }}
        </span>
        <span class="legend-time">
          {{
            shiftTypeDefaults[shift].startTime
              ? `${shiftTypeDefaults[shift].startTime}-${shiftTypeDefaults[shift].endTime}`
              : '无时段'
          }}
        </span>
      </li>
    </ul>

    <div v-loading="loading" class="grid-panel">
      <el-alert
        v-if="!communityId"
        type="warning"
        :closable="false"
        title="请先选择社区"
        description="排班以社区为范围，选定社区后才能列出该社区的服务人员。"
        show-icon
      />

      <el-empty v-else-if="!loading && staffRows.length === 0" description="该社区暂无服务人员">
        <p class="empty-tip">
          服务人员为系统用户中角色为「服务人员」的账号，且需在「服务人员」页绑定常驻社区后才会出现在排班表。
        </p>
        <router-link class="empty-link" to="/admin/staff-capabilities">前往「服务人员」页绑定人员</router-link>
      </el-empty>

      <div v-else class="grid-scroll">
        <div class="grid">
          <div class="grid-corner">服务人员</div>
          <div
            v-for="day in weekDays"
            :key="day.date"
            class="grid-head"
            :class="{ 'is-today': day.isToday }"
          >
            <span class="head-week">{{ day.label }}</span>
            <span class="head-date">{{ day.shortDate }}</span>
          </div>

          <template v-for="row in gridRows" :key="row.staff.staffId">
            <div class="grid-name">
              <span class="name-main">{{ row.staff.realName }}</span>
              <span class="name-sub">{{ row.staff.username }}</span>
            </div>

            <div
              v-for="cell in row.cells"
              :key="`${row.staff.staffId}-${cell.date}`"
              class="grid-cell"
              :class="{ 'is-today': cell.isToday, 'has-shift': !!cell.schedule }"
              :title="`点击设置 ${row.staff.realName} ${cell.date}（${cell.weekLabel}）的班次`"
              @click="openCellDialog(row.staff, cell.date)"
            >
              <template v-if="cell.schedule">
                <span
                  class="shift-chip"
                  :style="{ backgroundColor: cellShiftColor(cell).bg, color: cellShiftColor(cell).fg }"
                >
                  {{ cell.schedule.shiftLabel }}
                </span>
                <span v-if="cell.timeText" class="shift-time">{{ cell.timeText }}</span>
                <span v-else class="shift-time is-rest">无时段</span>
                <span v-if="cell.schedule.remark" class="shift-remark">{{ cell.schedule.remark }}</span>
                <button
                  type="button"
                  class="cell-clear"
                  title="清空该日排班"
                  @click.stop="removeCell(row.staff, cell.date)"
                >
                  ×
                </button>
              </template>
              <span v-else class="cell-empty">未排班</span>
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- 单元格小面板：单人行单日 -->
    <el-dialog
      v-model="cellDialogVisible"
      :title="cellTarget ? `${cellTarget.staffName} · ${cellTarget.date} ${cellTarget.weekLabel}` : '设置班次'"
      width="440px"
      append-to-body
    >
      <el-form label-width="76px" @submit.prevent>
        <el-form-item label="班次" required>
          <el-radio-group v-model="cellForm.shiftType" @change="handleShiftChange">
            <el-radio-button v-for="shift in SHIFT_TYPE_OPTIONS" :key="shift" :value="shift">
              {{ shiftTypeLabels[shift] }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="起止时间">
          <div class="time-row">
            <el-time-picker
              v-model="cellForm.startTime"
              format="HH:mm"
              value-format="HH:mm"
              placeholder="开始"
              :disabled="cellForm.shiftType === 'REST'"
            />
            <span class="time-sep">至</span>
            <el-time-picker
              v-model="cellForm.endTime"
              format="HH:mm"
              value-format="HH:mm"
              placeholder="结束"
              :disabled="cellForm.shiftType === 'REST'"
            />
          </div>
          <p class="form-hint">
            {{
              cellForm.shiftType === 'REST'
                ? '休息日不记录起止时间'
                : '留默认值即为该班次标准时段，可按需调整'
            }}
          </p>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="cellForm.remark" maxlength="60" show-word-limit placeholder="如「上午值班，下午外出」（可选）" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button v-if="cellSchedule" :disabled="saving" @click="removeCurrentCell">清空该日</el-button>
        <el-button @click="cellDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCell">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量设班：多人 × 多日期 一次提交 -->
    <el-dialog v-model="batchDialogVisible" title="批量设班" width="560px" append-to-body>
      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="服务人员" required>
          <el-select v-model="batchForm.staffIds" multiple filterable collapse-tags class="full-width" placeholder="选择服务人员">
            <el-option v-for="staff in staffRows" :key="staff.staffId" :label="staff.realName" :value="staff.staffId" />
          </el-select>
          <p class="form-hint">默认全选本周在册人员，可只挑需要值班的人</p>
        </el-form-item>

        <el-form-item label="排班日期" required>
          <el-checkbox-group v-model="batchForm.dates" class="date-row">
            <el-checkbox v-for="day in weekDays" :key="day.date" :value="day.date" border>
              {{ day.label }} {{ day.shortDate }}
            </el-checkbox>
          </el-checkbox-group>
          <div class="date-actions">
            <el-button text type="primary" size="small" @click="selectWholeWeek">选择整周</el-button>
            <el-button text size="small" @click="batchForm.dates = []">清空选择</el-button>
          </div>
        </el-form-item>

        <el-form-item label="班次" required>
          <el-radio-group v-model="batchForm.shiftType" @change="handleBatchShiftChange">
            <el-radio-button v-for="shift in SHIFT_TYPE_OPTIONS" :key="shift" :value="shift">
              {{ shiftTypeLabels[shift] }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="起止时间">
          <div class="time-row">
            <el-time-picker
              v-model="batchForm.startTime"
              format="HH:mm"
              value-format="HH:mm"
              placeholder="开始"
              :disabled="batchForm.shiftType === 'REST'"
            />
            <span class="time-sep">至</span>
            <el-time-picker
              v-model="batchForm.endTime"
              format="HH:mm"
              value-format="HH:mm"
              placeholder="结束"
              :disabled="batchForm.shiftType === 'REST'"
            />
          </div>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="batchForm.remark" maxlength="60" show-word-limit placeholder="批量备注（可选）" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBatch">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  padding: var(--spacing-md);
  margin-bottom: var(--spacing-md);
  border-radius: var(--radius-lg);
  background-color: var(--admin-card-bg);
  box-shadow: var(--shadow-card);
}

.community-select {
  width: 220px;
}

.week-nav {
  display: flex;
  gap: var(--spacing-xs);
}

.week-label {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  margin-left: auto;
}

.summary {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  margin: 0 0 var(--spacing-md);
  padding: 0;
  list-style: none;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.legend-chip {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.legend-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.grid-panel {
  padding: var(--spacing-md);
  border-radius: var(--radius-lg);
  background-color: var(--admin-card-bg);
  box-shadow: var(--shadow-card);
}

.empty-tip {
  max-width: 420px;
  margin: 0 auto var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.empty-link {
  color: var(--color-primary);
  font-size: var(--font-size-sm);
}

.grid-scroll {
  overflow-x: auto;
}

/* 网格：首列人员，其余七列为周一至周日 */
.grid {
  display: grid;
  grid-template-columns: 150px repeat(7, minmax(108px, 1fr));
  gap: 4px;
  min-width: 900px;
}

.grid-corner,
.grid-head {
  padding: var(--spacing-sm);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.grid-corner {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.grid-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.head-week {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.head-date {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.grid-head.is-today {
  background-color: var(--color-primary-bg);
}

.grid-head.is-today .head-week {
  color: var(--color-primary-dark);
}

.grid-name {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: var(--spacing-sm);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.name-main {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.name-sub {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 单元格：未排班浅灰，已排班按班次着色 */
.grid-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-height: 62px;
  padding: var(--spacing-xs);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--admin-card-bg);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.grid-cell:hover {
  border-color: var(--color-primary-light);
  background-color: var(--color-primary-bg);
}

.grid-cell.is-today {
  border-color: var(--color-primary-light);
}

.grid-cell.has-shift {
  border-style: solid;
}

.cell-empty {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.shift-chip {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
  white-space: nowrap;
}

.shift-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.shift-time.is-rest {
  color: var(--color-text-disabled);
}

.shift-remark {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 悬停删除：平时隐形，避免误触 */
.cell-clear {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 16px;
  height: 16px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.grid-cell:hover .cell-clear {
  opacity: 1;
}

.cell-clear:hover {
  background-color: var(--color-danger);
  color: #fff;
}

.full-width {
  width: 100%;
}

.time-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.time-sep {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.form-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.date-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.date-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-xs);
}

@media (max-width: 767px) {
  .toolbar-right {
    margin-left: 0;
  }
}
</style>
