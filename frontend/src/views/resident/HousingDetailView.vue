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
import HorizontalScroller from '@/components/common/HorizontalScroller.vue'
import type {
  IHousing,
  HousingStatus,
  IAvailableViewingTimeslot
} from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'

/**
 * 房源详情（居民端）：视觉与游客端一致（16:9 主图 + 缩略图 + 信息面板 + 描述/配套两栏）；
 * 右侧为居民版预约看房面板（DEF-056 详情页式交互：横拉时段卡点选 + 展开轻量确认，
 * 不再使用长表单；未登录引导登录）
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const housingId = Number(route.params.id)

const housing = ref<IHousing | null>(null)
const loading = ref(true)
const activeImage = ref(0)

/** 房源状态 → StatusTag 语义色 */
const statusTagType: Record<HousingStatus, 'completed' | 'pending' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'canceled',
  OFFLINE: 'canceled'
}

/** 租售类型标签（后端 V9 rent_type 白名单 RENT/SALE，与列表页筛选用语一致；未识别值原样展示兜底） */
const rentTypeLabels: Record<string, string> = {
  RENT: '出租',
  SALE: '出售'
}

/** 图片兜底：无图房源使用官方示例图（与列表页一致，按房源 ID 固定取图） */
const images = computed<string[]>(() => {
  if (!housing.value) return []
  return housing.value.images.length > 0
    ? housing.value.images
    : [`/images/housing-sample-${(housingId % 3) + 1}.png`]
})

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

/* 选中标识须带日期：模板降级展开路径下同一时段模板会按日期重复出现，仅靠 timeslotId 会串档 */
function cardKey(slot: IAvailableViewingTimeslot): string {
  return `${slot.date}#${slot.timeslotId}`
}

function timeText(value: string): string {
  return value.slice(0, 5)
}

function dateTextOf(date: string): string {
  const parts = date.split('-')
  const month = Number(parts[1])
  const day = Number(parts[2])
  return Number.isNaN(month) || Number.isNaN(day) ? date : `${month}月${day}日`
}

function weekdayOf(date: string): string {
  const parsed = new Date(`${date}T00:00:00`)
  return Number.isNaN(parsed.getTime()) ? '' : weekdayNames[parsed.getDay()]
}

const todayISO = toDateInput(new Date())

/** 横拉时段卡：按日期 + 开始时间排序的扁平列表（DEF-057 横拉列表数据源） */
const slotCards = computed(() =>
  [...slots.value].sort(
    (a, b) => a.date.localeCompare(b.date) || a.startTime.localeCompare(b.startTime)
  )
)

const selectedKey = ref<string | null>(null)

const selectedSlot = computed<IAvailableViewingTimeslot | null>(
  () => slots.value.find((slot) => cardKey(slot) === selectedKey.value) ?? null
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

function selectSlot(slot: IAvailableViewingTimeslot): void {
  if (slotDisabled(slot)) return
  selectedKey.value = cardKey(slot)
}

/** 取消选中：收起轻量确认区 */
function cancelSelection(): void {
  selectedKey.value = null
}

/* ------------------------------ 轻量确认（DEF-056） ------------------------------ */

const form = reactive({
  visitorName: '',
  contactPhone: '',
  remark: ''
})
const submitting = ref(false)

async function loadProfile(): Promise<void> {
  try {
    const profile = await getMyProfile()
    form.visitorName = profile.realName
    form.contactPhone = profile.phone
  } catch {
    /* 未登录或资料加载失败时留空手填 */
  }
}

/** 联系电话为后端必填（CreateViewingAppointmentDTO @NotBlank + 手机号格式），提交前本地校验 */
const phoneValid = computed(
  () => /^1[3-9]\d{9}$/.test(form.contactPhone.trim())
)

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  const slot = selectedSlot.value
  if (!slot) {
    ElMessage.warning('请先点选一个看房时段')
    return
  }
  if (!form.visitorName.trim()) {
    ElMessage.warning('请填写联系人姓名')
    return
  }
  if (!phoneValid.value) {
    ElMessage.warning('请填写正确的 11 位手机号')
    return
  }
  submitting.value = true
  try {
    await createViewingAppointment({
      housingId,
      appointmentDate: slot.date,
      startTime: slot.startTime,
      endTime: slot.endTime,
      visitorName: form.visitorName.trim(),
      contactPhone: form.contactPhone.trim(),
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

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    housing.value = await getHousingDetail(housingId)
    activeImage.value = 0
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
    <nav class="breadcrumb">
      <router-link to="/resident/housings">房源列表</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">{{ housing?.title ?? '房源详情' }}</span>
    </nav>

    <template v-if="housing">
      <div class="detail-layout">
        <!-- 左：16:9 主图 + 横向缩略图条（点击切换；单图时隐藏缩略图条） -->
        <div class="gallery-wrap">
          <div class="gallery">
            <img :src="images[activeImage]" :alt="housing.title" class="gallery-main" />
            <StatusTag
              class="gallery-status"
              on-image
              :label="housingStatusLabels[housing.status]"
              :type="statusTagType[housing.status]"
            />
          </div>
          <div v-if="images.length > 1" class="gallery-thumbs">
            <button
              v-for="(image, index) in images"
              :key="index"
              type="button"
              class="gallery-thumb"
              :class="{ active: activeImage === index }"
              @click="activeImage = index"
            >
              <img :src="image" :alt="`${housing.title} - 缩略图 ${index + 1}`" />
            </button>
          </div>
        </div>

        <div class="side-col">
          <!-- 右上：信息面板（与图区同高 stretch） -->
          <div class="info-panel">
            <h1 class="info-title">{{ housing.title }}</h1>
            <p class="info-address">{{ housing.communityName }} · {{ housing.houseLocation }}</p>

            <div class="info-rent">
              <span class="info-rent-amount">¥{{ housing.monthlyRent }}</span>
              <span class="info-rent-unit">/月</span>
              <span v-if="housing.deposit != null" class="info-rent-deposit">
                押金 ¥{{ housing.deposit }}
              </span>
            </div>

            <div v-if="(housing.tags?.length ?? 0) > 0" class="info-tags">
              <span v-for="tag in housing.tags" :key="tag" class="info-tag">{{ tag }}</span>
            </div>

            <!-- 实体无面积/楼层/朝向字段，信息网格取真实返回字段（接口文档字段为漂移命名） -->
            <dl class="info-grid">
              <div class="info-item">
                <dt>户型</dt>
                <dd>{{ housing.layout ?? '见标题' }}</dd>
              </div>
              <div class="info-item">
                <dt>出租方式</dt>
                <dd>{{ rentTypeLabels[housing.rentType] ?? housing.rentType }}</dd>
              </div>
              <div class="info-item">
                <dt>联系电话</dt>
                <dd>{{ housing.contactPhone ?? '—' }}</dd>
              </div>
              <div class="info-item">
                <dt>浏览量</dt>
                <dd>{{ housing.viewCount }}</dd>
              </div>
            </dl>
          </div>

          <!-- 右下：预约看房面板（DEF-056 详情页式：横拉点选时段 → 展开轻量确认，无长表单） -->
          <aside class="booking-panel">
            <h2 class="panel-title">预约看房</h2>

            <template v-if="isResident">
              <p class="panel-hint">点选可约时段后填写联系方式提交，等待管理员确认</p>
              <div v-loading="slotsLoading" class="panel-slots">
                <p v-if="!slotsLoading && slotCards.length === 0" class="slots-empty">
                  未来 {{ SLOT_WINDOW_DAYS }} 天暂无可约时段
                </p>
                <HorizontalScroller v-else>
                  <button
                    v-for="slot in slotCards"
                    :key="cardKey(slot)"
                    type="button"
                    class="slot-card"
                    :class="{
                      'is-selected': selectedKey === cardKey(slot),
                      'is-full': slotDisabled(slot)
                    }"
                    :disabled="slotDisabled(slot)"
                    @click="selectSlot(slot)"
                  >
                    <span class="slot-card-date">
                      {{ dateTextOf(slot.date) }}
                      <em>{{ slot.date === todayISO ? '今天' : weekdayOf(slot.date) }}</em>
                    </span>
                    <span class="slot-card-time">
                      {{ timeText(slot.startTime) }} ~ {{ timeText(slot.endTime) }}
                    </span>
                    <span class="slot-card-quota">
                      {{ slotDisabled(slot) ? '已约满' : `余 ${slot.maxBookings - slot.currentBookings}` }}
                    </span>
                  </button>
                </HorizontalScroller>
              </div>

              <!-- 轻量确认区：选中时段后展开，仅联系人/联系电话/备注三项（DEF-056） -->
              <transition name="confirm-fold">
                <div v-if="selectedSlot" class="confirm-panel">
                  <p class="confirm-slot-text">
                    已选时段：{{ selectedSlot.date }}（{{ weekdayOf(selectedSlot.date) }}）
                    {{ timeText(selectedSlot.startTime) }} ~ {{ timeText(selectedSlot.endTime) }}
                  </p>
                  <div class="confirm-fields">
                    <el-input
                      v-model="form.visitorName"
                      placeholder="联系人姓名"
                      maxlength="50"
                    />
                    <el-input
                      v-model="form.contactPhone"
                      placeholder="联系电话（必填）"
                      maxlength="11"
                    />
                  </div>
                  <el-input
                    v-model="form.remark"
                    type="textarea"
                    :rows="2"
                    placeholder="备注（选填）"
                    maxlength="500"
                    class="confirm-remark"
                  />
                  <div class="confirm-actions">
                    <el-button
                      type="primary"
                      class="submit-btn"
                      :loading="submitting"
                      @click="handleSubmit"
                    >
                      提交看房预约
                    </el-button>
                    <el-button text :disabled="submitting" @click="cancelSelection">
                      取消
                    </el-button>
                  </div>
                </div>
              </transition>
            </template>

            <template v-else>
              <div class="login-guide">
                <p>登录居民账号后即可预约看房</p>
                <router-link :to="`/auth/login?redirect=${encodeURIComponent(route.fullPath)}`">
                  <el-button type="primary" plain>去登录</el-button>
                </router-link>
              </div>
            </template>
          </aside>
        </div>
      </div>

      <!-- 下方两栏：左房源描述 / 右配套设施 -->
      <section class="description">
        <div class="description-col">
          <h2 class="description-title">房源描述</h2>
          <p class="description-paragraph">{{ housing.description || '暂无描述' }}</p>
        </div>

        <!-- 配套设施：静态占位（后端无配套字段，待字段落地后改数据驱动） -->
        <div class="description-col">
          <h2 class="description-title">配套设施</h2>
          <ul class="facility-list">
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="2" y="4" width="20" height="8" rx="2" />
                <path d="M6 8h12" />
                <path d="M8 16c0 1.5-1 2-1 3M12 16c0 1.5-1 2-1 3M16 16c0 1.5-1 2-1 3" />
              </svg>
              <span>空调</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="4" y="2" width="16" height="20" rx="2" />
                <circle cx="12" cy="13" r="5" />
                <path d="M8 5h4" />
                <circle cx="16.5" cy="5" r="0.5" />
              </svg>
              <span>洗衣机</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <rect x="6" y="2" width="12" height="20" rx="2" />
                <path d="M6 10h12" />
                <path d="M9 5v2M9 13v3" />
              </svg>
              <span>冰箱</span>
            </li>
            <li class="facility-item">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M2 9a15 15 0 0 1 20 0" />
                <path d="M5.5 12.5a10.5 10.5 0 0 1 13 0" />
                <path d="M9 16a6 6 0 0 1 6 0" />
                <circle cx="12" cy="19.5" r="0.5" />
              </svg>
              <span>WiFi</span>
            </li>
          </ul>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 24em;
}

/* 左图右栏布局：stretch 使信息面板与图区同高 */
.detail-layout {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: var(--spacing-lg);
  align-items: start;
}

.gallery-wrap {
  display: flex;
  flex-direction: column;
}

/* 16:9 微圆角大展示区 */
.gallery {
  position: relative;
  border-radius: var(--radius-lg);
  overflow: hidden;
  aspect-ratio: 16 / 9;
  background: var(--color-bg-hover);
}

.gallery-main {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.gallery-status {
  position: absolute;
  top: var(--spacing-md);
  left: var(--spacing-md);
  z-index: 1;
}

/* 缩略图条：横向滚动，hover 才显滚动条；选中态主色描边 */
.gallery-thumbs {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  padding-bottom: var(--spacing-xs);
  overflow-x: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.gallery-thumbs:hover {
  scrollbar-color: var(--color-text-disabled) transparent;
}

.gallery-thumbs::-webkit-scrollbar {
  height: 4px;
}

.gallery-thumbs::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: var(--radius-pill);
}

.gallery-thumbs:hover::-webkit-scrollbar-thumb {
  background: var(--color-text-disabled);
}

.gallery-thumb {
  flex-shrink: 0;
  width: 120px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: var(--radius-md);
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: var(--color-bg-hover);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.gallery-thumb.active {
  border-color: var(--color-primary);
}

.gallery-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 右栏：信息面板 + 预约面板 */
.side-col {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.info-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.info-title {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.info-address {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 价格行下方细分隔线 */
.info-rent {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  padding-bottom: var(--spacing-sm);
  border-bottom: 1px solid var(--color-border);
}

.info-rent-amount {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.info-rent-unit {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-rent-deposit {
  margin-left: auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-tags {
  display: flex;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.info-tag {
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

/* 信息网格：4 列单行，窄屏 2 列 */
.info-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--spacing-md) var(--spacing-sm);
}

.info-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-xs);
}

.info-item dd {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 预约面板 */
.booking-panel {
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
  min-height: 96px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  margin-bottom: var(--spacing-md);
}

.slots-empty {
  text-align: center;
  color: var(--color-text-disabled);
  padding: var(--spacing-lg) 0;
}

/* 横拉时段卡（DEF-057）：日期 + 时间 + 余量三行，选中主色描边，约满置灰 */
.slot-card {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  min-width: 118px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: #fff;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.slot-card:not(.is-full):hover {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.slot-card.is-selected {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
  box-shadow: var(--shadow-md);
}

.slot-card.is-full {
  background-color: var(--color-bg);
  cursor: not-allowed;
  opacity: 0.55;
}

.slot-card-date {
  display: inline-flex;
  align-items: baseline;
  gap: 3px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.slot-card-date em {
  font-style: normal;
  color: var(--color-text-disabled);
}

.slot-card-time {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  font-family: var(--font-family-mono);
  color: var(--color-text-primary);
}

.slot-card-quota {
  font-size: var(--font-size-xs);
  color: var(--color-success);
}

.slot-card.is-full .slot-card-quota {
  color: var(--color-text-disabled);
}

/* 选中态统一主色文字 */
.slot-card.is-selected .slot-card-date,
.slot-card.is-selected .slot-card-time,
.slot-card.is-selected .slot-card-date em {
  color: var(--color-primary);
}

/* 轻量确认区（DEF-056）：选中时段后折叠展开 */
.confirm-panel {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  padding-top: var(--spacing-md);
  border-top: 1px solid var(--color-border);
}

.confirm-slot-text {
  margin: 0;
  color: var(--color-primary);
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-sm);
  font-family: var(--font-family-mono);
}

.confirm-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm);
}

.confirm-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.submit-btn {
  flex: 1;
}

/* 确认区折叠动画：高度 + 透明度过渡，收起时避免占位 */
.confirm-fold-enter-active,
.confirm-fold-leave-active {
  transition: opacity 0.18s ease;
}

.confirm-fold-enter-from,
.confirm-fold-leave-to {
  opacity: 0;
}

.login-guide {
  text-align: center;
  color: var(--color-text-secondary);
  padding: var(--spacing-lg) 0;
}

.login-guide p {
  margin-bottom: var(--spacing-md);
}

/* 描述区：左右两栏（描述 / 配套设施），节标题蓝竖条与全站一致 */
.description {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-xl);
  margin-top: var(--spacing-xl);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.description-col {
  min-width: 0;
}

.description-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-md);
}

.description-title::before {
  content: '';
  width: 4px;
  height: 18px;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
}

.description-paragraph {
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  white-space: pre-wrap;
}

/* 配套设施：线性图标 + 名称 */
.facility-list {
  display: flex;
  gap: var(--spacing-xl);
  flex-wrap: wrap;
  padding-top: var(--spacing-sm);
}

.facility-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}

.facility-item svg {
  width: 30px;
  height: 30px;
}

/* 响应式：窄屏图上信息下、信息网格 2 列、描述区降单栏、确认字段单列 */
@media (max-width: 1024px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }

  .info-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .description {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .confirm-fields {
    grid-template-columns: 1fr;
  }
}
</style>
