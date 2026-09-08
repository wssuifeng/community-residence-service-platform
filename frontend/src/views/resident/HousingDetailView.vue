<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { useUserStore } from '@/store/user'
import {
  getHousingDetail,
  recordHousingView,
  listViewingAvailableSlots,
  listHousingTimeslots,
  createViewingAppointment
} from '@/api/housing'
import { getMyProfile } from '@/api/resident'
import type {
  IHousing,
  HousingStatus,
  IAvailableViewingTimeslot
} from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import { formatDate } from '@/utils/date'

/**
 * 房源详情（居民端）：大图与房源信息 + 看房时段网格点选
 * + 提交看房预约（仅已登录居民可提交，游客提示登录）
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const housingId = Number(route.params.id)

const housing = ref<IHousing | null>(null)
const loading = ref(true)
const activeImage = ref('')

const housingTagTypeMap: Record<HousingStatus, 'completed' | 'pending' | 'processing' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'processing',
  OFFLINE: 'canceled'
}

const isResident = computed(() => userStore.isLoggedIn && userStore.role === 'RESIDENT')

/* ------------------------------ 看房时段 ------------------------------ */

function toDateInput(date: Date): string {
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const SLOT_WINDOW_DAYS = 14
const windowStart = toDateInput(new Date())
const windowEnd = toDateInput(new Date(Date.now() + SLOT_WINDOW_DAYS * 24 * 60 * 60 * 1000))

const weekdayNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const slots = ref<IAvailableViewingTimeslot[]>([])
const slotsLoading = ref(false)
const selectedTimeslotId = ref<number | null>(null)

interface DateGroup {
  date: string
  weekday: string
  isToday: boolean
  slots: IAvailableViewingTimeslot[]
}

const dateGroups = computed<DateGroup[]>(() => {
  const map = new Map<string, IAvailableViewingTimeslot[]>()
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

const selectedSlot = computed<IAvailableViewingTimeslot | null>(
  () => slots.value.find((slot) => slot.timeslotId === selectedTimeslotId.value) ?? null
)

async function loadSlots(): Promise<void> {
  slotsLoading.value = true
  try {
    slots.value = await listViewingAvailableSlots(housingId, {
      startDate: windowStart,
      endDate: windowEnd
    })
  } catch {
    /* available-slots 接口后端暂缺（BE-ISSUE-5）：降级取周循环模板自行展开未来窗口 */
    try {
      const templates = await listHousingTimeslots(housingId)
      const expanded: IAvailableViewingTimeslot[] = []
      const today = new Date()
      for (let offset = 0; offset <= SLOT_WINDOW_DAYS; offset += 1) {
        const day = new Date(today.getTime() + offset * 24 * 60 * 60 * 1000)
        const dow = day.getDay()
        for (const tpl of templates) {
          if (tpl.dayOfWeek !== dow || !tpl.isAvailable) continue
          expanded.push({
            timeslotId: tpl.id,
            date: toDateInput(day),
            startTime: tpl.startTime,
            endTime: tpl.endTime,
            maxBookings: 1,
            currentBookings: 0,
            status: 'AVAILABLE'
          })
        }
      }
      slots.value = expanded
    } catch {
      /* 模板也取不到时保留空态 */
    }
  } finally {
    slotsLoading.value = false
  }
}

function slotDisabled(slot: IAvailableViewingTimeslot): boolean {
  return slot.status === 'FULL' || slot.currentBookings >= slot.maxBookings
}

function slotTooltip(slot: IAvailableViewingTimeslot): string {
  return slotDisabled(slot)
    ? `已约满（${slot.currentBookings}/${slot.maxBookings}）`
    : `剩余名额 ${slot.maxBookings - slot.currentBookings} / ${slot.maxBookings}`
}

function selectSlot(slot: IAvailableViewingTimeslot): void {
  if (slotDisabled(slot)) return
  selectedTimeslotId.value = slot.timeslotId
}

/* ------------------------------ 预约表单 ------------------------------ */

const form = reactive({
  visitorName: '',
  visitorPhone: '',
  visitorCount: 1,
  remark: ''
})
const submitting = ref(false)

async function loadProfile(): Promise<void> {
  try {
    const profile = await getMyProfile()
    form.visitorName = profile.realName
    form.visitorPhone = profile.phone
  } catch {
    /* 未登录或资料加载失败时留空手填 */
  }
}

async function handleSubmit(): Promise<void> {
  if (selectedTimeslotId.value === null) {
    ElMessage.warning('请先点选一个看房时段')
    return
  }
  if (!form.visitorName.trim()) {
    ElMessage.warning('请填写您的姓名')
    return
  }
  submitting.value = true
  try {
    const slot = selectedSlot.value
    if (!slot) {
      ElMessage.warning('请先点选一个看房时段')
      return
    }
    await createViewingAppointment({
      housingId,
      appointmentDate: slot.date,
      startTime: slot.startTime,
      endTime: slot.endTime,
      visitorName: form.visitorName.trim(),
      contactPhone: form.visitorPhone.trim(),
      remark: form.remark.trim() || undefined
    })
    ElMessage.success('看房预约已提交，等待管理员确认')
    router.push('/resident/viewing-appointments')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交看房预约失败')
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 初始化 ------------------------------ */

function fallbackCover(): string {
  return `/images/housing-sample-${(housingId % 3) + 1}.png`
}

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    housing.value = await getHousingDetail(housingId)
    const images = housing.value.images ?? []
    activeImage.value = images.length > 0 ? images[0] : fallbackCover()
    /* 记录浏览（公开接口，失败静默） */
    recordHousingView(housingId).catch(() => undefined)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源详情失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadDetail()
  loadSlots()
  if (isResident.value) loadProfile()
})
</script>

<template>
  <section v-loading="loading" class="housing-detail">
    <router-link to="/resident/housings" class="back-link">← 返回房源列表</router-link>

    <template v-if="housing">
      <div class="detail-grid">
        <!-- 左：大图 + 信息 -->
        <div class="detail-main">
          <div class="gallery">
            <div class="main-image">
              <img :src="activeImage" :alt="housing.title" />
              <StatusTag
                class="image-status"
                :label="housingStatusLabels[housing.status]"
                :type="housingTagTypeMap[housing.status]"
              />
            </div>
            <div v-if="(housing.images ?? []).length > 1" class="thumbs">
              <button
                v-for="image in housing.images"
                :key="image"
                type="button"
                class="thumb"
                :class="{ 'is-active': activeImage === image }"
                @click="activeImage = image"
              >
                <img :src="image" alt="房源图片" />
              </button>
            </div>
          </div>

          <h1 class="housing-title">{{ housing.title }}</h1>
          <p class="housing-location">{{ housing.communityName }} · {{ housing.houseAddress }}</p>

          <div v-if="(housing.tags?.length ?? 0) > 0" class="housing-tags">
            <span v-for="tag in housing.tags" :key="tag" class="tag-chip">{{ tag }}</span>
          </div>

          <dl class="info-grid">
            <div class="info-item">
              <dt>月租金</dt>
              <dd class="rent">￥{{ housing.monthlyRent }}<span class="unit">/月</span></dd>
            </div>
            <div class="info-item">
              <dt>押金</dt>
              <dd>{{ housing.depositAmount != null ? `￥${housing.depositAmount}` : '面议' }}</dd>
            </div>
            <div class="info-item">
              <dt>可入住日期</dt>
              <dd>{{ formatDate(housing.availableDate) }}</dd>
            </div>
            <div class="info-item">
              <dt>联系人</dt>
              <dd>{{ housing.contactPerson }}<template v-if="housing.contactPhone">（{{ housing.contactPhone }}）</template></dd>
            </div>
            <div class="info-item">
              <dt>浏览量</dt>
              <dd>{{ housing.viewCount }} 次</dd>
            </div>
            <div class="info-item">
              <dt>发布时间</dt>
              <dd>{{ formatDate(housing.createdAt) }}</dd>
            </div>
          </dl>

          <div class="description">
            <h2>房源描述</h2>
            <p>{{ housing.description || '暂无描述' }}</p>
          </div>
        </div>

        <!-- 右：看房预约 -->
        <aside class="booking-panel">
          <h2 class="panel-title">预约看房</h2>

          <template v-if="isResident">
            <p class="panel-hint">点选下方可约时段，提交后等待管理员确认</p>
            <div v-loading="slotsLoading" class="panel-slots">
              <p v-if="!slotsLoading && dateGroups.length === 0" class="slots-empty">
                未来 {{ SLOT_WINDOW_DAYS }} 天暂无可约时段
              </p>
              <div v-for="group in dateGroups" :key="group.date" class="date-group">
                <div class="date-label">
                  <span class="date-text">{{ group.date }}</span>
                  <span class="date-week">{{ group.isToday ? '今天' : group.weekday }}</span>
                </div>
                <div class="slot-grid">
                  <el-tooltip v-for="slot in group.slots" :key="slot.timeslotId" :content="slotTooltip(slot)" placement="top">
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

            <p v-if="selectedSlot" class="selected-slot-text">
              已选时段：{{ selectedSlot.date }} {{ selectedSlot.startTime }} ~ {{ selectedSlot.endTime }}
            </p>
            <el-form label-width="70px" class="booking-form" @submit.prevent>
              <el-form-item label="姓名" required>
                <el-input v-model="form.visitorName" placeholder="看房人姓名" maxlength="30" />
              </el-form-item>
              <el-form-item label="电话" required>
                <el-input v-model="form.visitorPhone" placeholder="联系电话" maxlength="20" />
              </el-form-item>
              <el-form-item label="人数">
                <el-input-number v-model="form.visitorCount" :min="1" :max="10" />
              </el-form-item>
              <el-form-item label="备注">
                <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="选填" maxlength="200" />
              </el-form-item>
              <el-button
                type="primary"
                round
                class="submit-btn"
                :loading="submitting"
                :disabled="selectedTimeslotId === null"
                @click="handleSubmit"
              >
                提交看房预约
              </el-button>
            </el-form>
          </template>

          <template v-else>
            <div class="login-guide">
              <img src="/images/empty-state.png" alt="请先登录" />
              <p>登录居民账号后即可预约看房</p>
              <router-link :to="`/auth/login?redirect=${encodeURIComponent(route.fullPath)}`">
                <el-button type="primary" plain round>去登录</el-button>
              </router-link>
            </div>
          </template>
        </aside>
      </div>
    </template>
  </section>
</template>

<style scoped>
.back-link {
  display: inline-block;
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: var(--spacing-lg);
  align-items: start;
}

.main-image {
  position: relative;
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background-color: var(--color-bg);
}

.main-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-status {
  position: absolute;
  top: var(--spacing-md);
  right: var(--spacing-md);
  box-shadow: var(--shadow-sm);
}

.thumbs {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  overflow-x: auto;
}

.thumb {
  flex-shrink: 0;
  width: 96px;
  aspect-ratio: 4 / 3;
  border: 2px solid transparent;
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
  padding: 0;
  background: none;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.thumb.is-active {
  border-color: var(--color-primary);
}

.housing-title {
  margin-top: var(--spacing-lg);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.housing-location {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.housing-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
  margin-top: var(--spacing-sm);
}

.tag-chip {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: var(--spacing-md);
  margin-top: var(--spacing-lg);
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.info-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.info-item dd {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.info-item .rent {
  color: var(--color-danger);
  font-size: var(--font-size-lg);
}

.rent .unit {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}

.description {
  margin-top: var(--spacing-lg);
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.description h2 {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-sm);
}

.description p {
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

/* 右侧预约面板 */
.booking-panel {
  position: sticky;
  top: var(--spacing-md);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.panel-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-sm);
}

.panel-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-md);
}

.panel-slots {
  min-height: 80px;
  max-height: 320px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.slots-empty {
  text-align: center;
  color: var(--color-text-disabled);
  padding: var(--spacing-lg) 0;
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
  font-size: var(--font-size-sm);
}

.date-week {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.slot-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--spacing-sm);
}

.slot-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--spacing-xs);
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
  cursor: not-allowed;
  opacity: 0.6;
}

.slot-time {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  font-family: var(--font-family-mono);
}

.slot-quota {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.booking-form {
  border-top: 1px solid var(--color-border);
  padding-top: var(--spacing-md);
}

.selected-slot-text {
  margin-bottom: var(--spacing-sm);
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-sm);
  font-family: var(--font-family-mono);
}

.submit-btn {
  width: 100%;
}

.login-guide {
  text-align: center;
  color: var(--color-text-secondary);
  padding: var(--spacing-lg) 0;
}

.login-guide img {
  width: 100px;
  margin: 0 auto var(--spacing-md);
}

.login-guide p {
  margin-bottom: var(--spacing-md);
}

@media (max-width: 1023px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
