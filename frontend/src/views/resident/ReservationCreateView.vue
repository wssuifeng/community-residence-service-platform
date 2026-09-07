<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCommunityList, getResourceList } from '@/api/community'
import { listAvailableTimeslots, createReservation } from '@/api/reservation'
import { getMyProfile } from '@/api/resident'
import type { ICommunity, IPublicResource } from '@/types/modules/community'
import type { IAvailableTimeslot } from '@/types/modules/reservation'
import type { IResident } from '@/types/modules/resident'

/**
 * 创建预约：选资源 → 按日期查可用时段（14 天窗口）→ 时段网格点选
 * （可约格子高亮可选、约满置灰带 tooltip）→ 填写信息提交
 */

interface ResourceOption {
  id: number
  name: string
  communityName: string
  capacity?: number
  location?: string
  label: string
}

const router = useRouter()

const communities = ref<ICommunity[]>([])
const resources = ref<ResourceOption[]>([])
const resourceId = ref<number | null>(null)
const slots = ref<IAvailableTimeslot[]>([])
const slotsLoading = ref(false)
const submitting = ref(false)

const selectedTimeslotId = ref<number | null>(null)

const form = reactive({
  participants: 1,
  purpose: '',
  contactPhone: '',
  remark: ''
})

/** 资源加载：社区列表 → 逐社区拉公共资源，聚合成带社区名的选项 */
async function loadResources(): Promise<void> {
  try {
    const communityPage = await getCommunityList({ page: 1, size: 100, status: 'ACTIVE' })
    communities.value = communityPage.records
    const all: ResourceOption[] = []
    await Promise.all(
      communityPage.records.map(async (community) => {
        const resourcePage = await getResourceList(community.id, {
          page: 1,
          size: 100,
          status: 'AVAILABLE'
        })
        resourcePage.records.forEach((resource: IPublicResource) => {
          all.push({
            id: resource.id,
            name: resource.name,
            communityName: community.name,
            capacity: resource.capacity,
            location: resource.location,
            label: `${community.name} · ${resource.name}`
          })
        })
      })
    )
    resources.value = all
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载公共资源失败')
  }
}

/** 预填联系电话（居民个人资料） */
async function loadProfile(): Promise<void> {
  try {
    const profile: IResident = await getMyProfile()
    form.contactPhone = profile.phone
  } catch {
    /* 资料加载失败不阻塞预约流程，电话可手填 */
  }
}

/* ------------------------------ 时段网格 ------------------------------ */

function toDateInput(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const SLOT_WINDOW_DAYS = 14

const windowStart = toDateInput(new Date())
const windowEnd = toDateInput(new Date(Date.now() + SLOT_WINDOW_DAYS * 24 * 60 * 60 * 1000))

const weekdayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

interface DateGroup {
  date: string
  weekday: string
  isToday: boolean
  slots: IAvailableTimeslot[]
}

/** 时段按日期分组，仅保留有格子的日期 */
const dateGroups = computed<DateGroup[]>(() => {
  const map = new Map<string, IAvailableTimeslot[]>()
  slots.value.forEach((slot) => {
    const list = map.get(slot.date) ?? []
    list.push(slot)
    map.set(slot.date, list)
  })
  const today = toDateInput(new Date())
  return Array.from(map.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([date, list]) => ({
      date,
      weekday: weekdayNames[new Date(`${date}T00:00:00`).getDay()],
      isToday: date === today,
      slots: list.sort((a, b) => a.startTime.localeCompare(b.startTime))
    }))
})

const selectedSlot = computed<IAvailableTimeslot | null>(
  () => slots.value.find((slot) => slot.timeslotId === selectedTimeslotId.value) ?? null
)

async function loadSlots(): Promise<void> {
  if (resourceId.value === null) return
  slotsLoading.value = true
  selectedTimeslotId.value = null
  try {
    slots.value = await listAvailableTimeslots(resourceId.value, {
      startDate: windowStart,
      endDate: windowEnd
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载可预约时段失败')
  } finally {
    slotsLoading.value = false
  }
}

function handleResourceChange(): void {
  slots.value = []
  loadSlots()
}

function slotDisabled(slot: IAvailableTimeslot): boolean {
  return slot.status === 'FULL' || slot.currentBookings >= slot.maxBookings
}

function slotTooltip(slot: IAvailableTimeslot): string {
  return slotDisabled(slot)
    ? `已约满（${slot.currentBookings}/${slot.maxBookings}）`
    : `剩余名额 ${slot.maxBookings - slot.currentBookings} / ${slot.maxBookings}`
}

function selectSlot(slot: IAvailableTimeslot): void {
  if (slotDisabled(slot)) return
  selectedTimeslotId.value = slot.timeslotId
}

const selectedResource = computed<ResourceOption | null>(
  () => resources.value.find((item) => item.id === resourceId.value) ?? null
)

/* ------------------------------ 提交 ------------------------------ */

async function handleSubmit(): Promise<void> {
  if (resourceId.value === null) {
    ElMessage.warning('请先选择要预约的公共资源')
    return
  }
  if (selectedTimeslotId.value === null) {
    ElMessage.warning('请在时段网格中点选一个可预约的时段')
    return
  }
  submitting.value = true
  try {
    await createReservation({
      timeslotId: selectedTimeslotId.value,
      participants: form.participants,
      purpose: form.purpose.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      remark: form.remark.trim() || undefined
    })
    ElMessage.success('预约已提交，等待管理员审核')
    router.push('/resident/reservations')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交预约失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadResources()
  loadProfile()
})
</script>

<template>
  <section class="reservation-create">
    <header class="page-head">
      <h1>发起预约</h1>
      <p class="page-head-sub">选择社区公共资源，点选可用时段即可提交预约申请</p>
    </header>

    <el-steps :active="selectedTimeslotId ? 2 : resourceId ? 1 : 0" align-center class="steps">
      <el-step title="选择资源" />
      <el-step title="点选时段" />
      <el-step title="确认提交" />
    </el-steps>

    <!-- 第一步：选资源 -->
    <div class="form-card">
      <h2 class="form-card-title">① 选择公共资源</h2>
      <el-select
        v-model="resourceId"
        filterable
        placeholder="搜索或选择社区公共资源（如羽毛球馆）"
        style="width: 100%"
        @change="handleResourceChange"
      >
        <el-option
          v-for="resource in resources"
          :key="resource.id"
          :value="resource.id"
          :label="resource.label"
        />
      </el-select>
      <p v-if="selectedResource" class="resource-brief">
        <span>{{ selectedResource.communityName }} · {{ selectedResource.name }}</span>
        <span v-if="selectedResource.location">｜位置：{{ selectedResource.location }}</span>
        <span v-if="selectedResource.capacity">｜容纳 {{ selectedResource.capacity }} 人</span>
      </p>
    </div>

    <!-- 第二步：时段网格 -->
    <div class="form-card">
      <h2 class="form-card-title">② 点选可预约时段</h2>
      <template v-if="resourceId === null">
        <p class="slots-hint">请先选择公共资源，再查看未来 {{ SLOT_WINDOW_DAYS }} 天的可预约时段</p>
      </template>
      <template v-else>
        <div v-loading="slotsLoading" class="slots-body">
          <p v-if="!slotsLoading && dateGroups.length === 0" class="slots-hint">
            该资源未来 {{ SLOT_WINDOW_DAYS }} 天暂无可预约时段，请稍后再试
          </p>
          <div v-for="group in dateGroups" :key="group.date" class="date-group">
            <div class="date-label">
              <span class="date-text">{{ group.date }}</span>
              <span class="date-week">{{ group.isToday ? '今天' : group.weekday }}</span>
            </div>
            <div class="slot-grid">
              <el-tooltip
                v-for="slot in group.slots"
                :key="slot.timeslotId"
                :content="slotTooltip(slot)"
                :disabled="false"
                placement="top"
              >
                <button
                  type="button"
                  class="slot-cell"
                  :class="{
                    'is-selected': selectedTimeslotId === slot.timeslotId,
                    'is-full': slotDisabled(slot)
                  }"
                  :disabled="slotDisabled(slot)"
                  @click="selectSlot(slot)"
                >
                  <span class="slot-time">{{ slot.startTime }} ~ {{ slot.endTime }}</span>
                  <span class="slot-quota">
                    {{ slotDisabled(slot) ? '已约满' : `余 ${slot.maxBookings - slot.currentBookings}` }}
                  </span>
                </button>
              </el-tooltip>
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- 第三步：预约信息 -->
    <div class="form-card">
      <h2 class="form-card-title">③ 填写预约信息</h2>
      <el-form label-width="90px" class="info-form">
        <el-form-item label="所选时段">
          <span v-if="selectedSlot" class="selected-slot-text">
            {{ selectedSlot.date }} {{ selectedSlot.startTime }} ~ {{ selectedSlot.endTime }}
          </span>
          <span v-else class="unselected-slot-text">尚未选择时段</span>
        </el-form-item>
        <el-form-item label="使用人数" required>
          <el-input-number v-model="form.participants" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="联系电话" required>
          <el-input v-model="form.contactPhone" placeholder="用于预约确认联系" maxlength="20" style="width: 260px" />
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
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            round
            size="large"
            :loading="submitting"
            :disabled="selectedTimeslotId === null"
            @click="handleSubmit"
          >
            提交预约
          </el-button>
          <el-button round size="large" @click="router.back()">返回</el-button>
        </el-form-item>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.reservation-create {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
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

.steps {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg) 0;
}

.form-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.form-card-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-md);
}

.resource-brief {
  margin-top: var(--spacing-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.slots-hint {
  color: var(--color-text-disabled);
  text-align: center;
  padding: var(--spacing-lg) 0;
}

.slots-body {
  min-height: 120px;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.date-group {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.date-label {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
}

.date-text {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.date-week {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 时段格子网格：亮点交互 */
.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--spacing-sm);
}

.slot-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: #fff;
  cursor: pointer;
  transition: all 0.15s ease;
}

.slot-cell:not(.is-full):hover {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.slot-cell.is-selected {
  border-color: var(--color-primary);
  background-color: var(--color-primary);
  box-shadow: var(--shadow-md);
}

.slot-cell.is-selected .slot-time,
.slot-cell.is-selected .slot-quota {
  color: #fff;
}

.slot-cell.is-full {
  background-color: var(--color-bg);
  border-color: var(--color-border);
  cursor: not-allowed;
  opacity: 0.6;
}

.slot-time {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  font-family: var(--font-family-mono);
}

.slot-quota {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.info-form {
  max-width: 640px;
}

.selected-slot-text {
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
  font-family: var(--font-family-mono);
}

.unselected-slot-text {
  color: var(--color-text-disabled);
}
</style>
