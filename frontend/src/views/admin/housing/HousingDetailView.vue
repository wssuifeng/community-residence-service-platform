<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import {
  getHousingDetail,
  updateHousing,
  updateHousingStatus,
  listHousingTimeslots,
  createHousingTimeslot,
  updateHousingTimeslot,
  deleteHousingTimeslot,
  listViewingAppointments
} from '@/api/housing'
import type {
  IHousing,
  IHousingTimeslot,
  IViewingAppointment,
  HousingStatus,
  HousingTimeslotSaveDTO,
  ViewingAppointmentStatus
} from '@/types/modules/housing'
import { housingStatusLabels, viewingAppointmentStatusLabels } from '@/types/modules/housing'
import { formatDateTime } from '@/utils/date'

/**
 * 房源详情（管理端）：面包屑 + 图集/信息摘要白卡 + 看房时段配置 + 关联看房预约。
 * 信息展示与保存载荷均对齐后端 HousingVO/CreateHousingDTO 真实字段
 * （押金 deposit、图片逗号分隔串；接口文档的 availableDate/contactPerson 等漂移字段后端不返回）。
 * 「房源管理」面包屑即返回入口；?hash=#timeslots 锚定时段区（列表卡片「时段」动作落点）。
 */

const route = useRoute()
const router = useRouter()
const housingId = Number(route.params.id)

const housing = ref<IHousing | null>(null)
const loading = ref(true)

const tagTypeMap: Record<HousingStatus, 'completed' | 'pending' | 'processing' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'processing',
  OFFLINE: 'canceled'
}

/** 金额千分位（与列表卡片口径一致） */
function formatRent(value: number): string {
  return Number(value).toLocaleString('zh-CN')
}

/* ------------------------------ 图集 ------------------------------ */

const activeImage = ref(0)

/** 图集兜底：无图房源使用官方示例图（与列表页轮换策略一致，固定第 1 张） */
const galleryImages = computed<string[]>(() => {
  if (!housing.value) return []
  return housing.value.images.length > 0 ? housing.value.images : ['/images/housing-sample-1.png']
})

/* ------------------------------ 信息编辑 ------------------------------ */

const editable = ref(false)
const saving = ref(false)

const form = reactive({
  title: '',
  description: '',
  monthlyRent: 0,
  deposit: undefined as number | undefined,
  imagesText: ''
})

function fillForm(source: IHousing): void {
  form.title = source.title
  form.description = source.description
  form.monthlyRent = source.monthlyRent
  form.deposit = source.deposit ?? undefined
  form.imagesText = source.images.join('\n')
}

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    housing.value = await getHousingDetail(housingId)
    fillForm(housing.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源详情失败')
  } finally {
    loading.value = false
  }
}

function toggleEdit(): void {
  if (editable.value && housing.value) fillForm(housing.value)
  editable.value = !editable.value
}

async function handleSave(): Promise<void> {
  if (!housing.value) return
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    await updateHousing(housingId, {
      houseId: housing.value.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      deposit: form.deposit,
      images: form.imagesText
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0)
        .join(',')
    })
    ElMessage.success('房源信息已保存')
    editable.value = false
    activeImage.value = 0
    loadDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存房源信息失败')
  } finally {
    saving.value = false
  }
}

/** 上架/下架切换 */
async function handleToggleStatus(): Promise<void> {
  if (!housing.value) return
  const goingOffline = housing.value.status !== 'OFFLINE'
  try {
    if (goingOffline) {
      await ElMessageBox.confirm('下架后居民端不再展示该房源，确认下架？', '下架房源', {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await updateHousingStatus(housingId, { status: 'OFFLINE' })
    } else {
      await updateHousingStatus(housingId, { status: 'AVAILABLE' })
    }
    ElMessage.success(goingOffline ? '房源已下架' : '房源已上架')
    loadDetail()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '状态切换失败')
  }
}

/* ------------------------------ 时段配置管理 ------------------------------ */

const timeslotRecords = ref<IHousingTimeslot[]>([])
const timeslotLoading = ref(false)

async function loadTimeslots(): Promise<void> {
  timeslotLoading.value = true
  try {
    timeslotRecords.value = await listHousingTimeslots(housingId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看房时段失败')
  } finally {
    timeslotLoading.value = false
  }
}

/* 新建/编辑时段对话框 */
const slotDialogVisible = ref(false)
const editingSlotId = ref<number | null>(null)
const slotSaving = ref(false)

const slotForm = reactive<HousingTimeslotSaveDTO>({
  dayOfWeek: 1,
  startTime: '',
  endTime: '',
  isAvailable: 1
})

function openSlotCreate(): void {
  editingSlotId.value = null
  Object.assign(slotForm, { dayOfWeek: 1, startTime: '', endTime: '', isAvailable: 1 })
  slotDialogVisible.value = true
}

function openSlotEdit(row: IHousingTimeslot): void {
  editingSlotId.value = row.id
  Object.assign(slotForm, {
    dayOfWeek: row.dayOfWeek,
    startTime: row.startTime,
    endTime: row.endTime,
    isAvailable: row.isAvailable
  })
  slotDialogVisible.value = true
}

async function handleSlotSave(): Promise<void> {
  if (!slotForm.dayOfWeek || !slotForm.startTime || !slotForm.endTime) {
    ElMessage.warning('请完整填写星期与起止时间')
    return
  }
  slotSaving.value = true
  try {
    const payload: HousingTimeslotSaveDTO = {
      dayOfWeek: slotForm.dayOfWeek,
      startTime: slotForm.startTime,
      endTime: slotForm.endTime,
      isAvailable: slotForm.isAvailable
    }
    if (editingSlotId.value === null) {
      await createHousingTimeslot(housingId, payload)
      ElMessage.success('时段已创建')
    } else {
      await updateHousingTimeslot(editingSlotId.value, payload)
      ElMessage.success('时段已更新')
    }
    slotDialogVisible.value = false
    loadTimeslots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存时段失败')
  } finally {
    slotSaving.value = false
  }
}

/** 星期名：后端 dayOfWeek 为 ISO 1~7（7=周日），取模映射「日一二三四五六」 */
function dayOfWeekName(dayOfWeek: number): string {
  return '日一二三四五六'[dayOfWeek % 7]
}

async function handleSlotDelete(row: IHousingTimeslot): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除 周${dayOfWeekName(row.dayOfWeek)} ${row.startTime} ~ ${row.endTime} 时段？已有预约时无法删除`,
      '删除时段',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'error' }
    )
    await deleteHousingTimeslot(row.id)
    ElMessage.success('时段已删除')
    loadTimeslots()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '删除时段失败')
  }
}

/* ------------------------------ 关联看房预约（最近 5 条，只读） ------------------------------ */

const viewingTagTypeMap: Record<ViewingAppointmentStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  TO_CONFIRM: 'pending',
  RESERVED: 'processing',
  COMPLETED: 'completed',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const relatedViewings = ref<IViewingAppointment[]>([])
const relatedLoading = ref(false)

async function loadRelatedViewings(): Promise<void> {
  relatedLoading.value = true
  try {
    const result = await listViewingAppointments({ page: 1, size: 5, housingId })
    relatedViewings.value = result.records
  } catch {
    relatedViewings.value = []
  } finally {
    relatedLoading.value = false
  }
}

function goBack(): void {
  /* 默认 Tab 为看房预约（R62 后房源挂牌归社区结构），返回落「全局房源」Tab */
  router.push({ path: '/admin/housings', query: { tab: 'list' } })
}

function goAllViewings(): void {
  router.push({ path: '/admin/housings', query: { tab: 'viewings' } })
}

onMounted(async () => {
  loadDetail()
  loadTimeslots()
  loadRelatedViewings()
  /* 列表卡片「时段」动作经 #timeslots 锚点直达时段区 */
  if (route.hash === '#timeslots') {
    await nextTick()
    document.getElementById('timeslots')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
})
</script>

<template>
  <section v-loading="loading" class="housing-detail-admin">
    <nav class="detail-breadcrumb" aria-label="面包屑">
      <el-link type="primary" :underline="'never'" @click="goBack">房源管理</el-link>
      <span class="breadcrumb-separator" aria-hidden="true">/</span>
      <span class="breadcrumb-current">{{ housing?.title || '房源详情' }}</span>
    </nav>

    <template v-if="housing">
      <div class="hero-grid">
        <!-- 左：图集（主图 + 缩略图条，单图时隐藏缩略图条） -->
        <div class="gallery-card">
          <div class="gallery">
            <img :src="galleryImages[activeImage]" :alt="housing.title" class="gallery-main" />
            <StatusTag
              class="gallery-status"
              on-image
              :label="housingStatusLabels[housing.status]"
              :type="tagTypeMap[housing.status]"
            />
          </div>
          <div v-if="galleryImages.length > 1" class="gallery-thumbs">
            <button
              v-for="(image, index) in galleryImages"
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

        <!-- 右：信息摘要 / 编辑表单 -->
        <div class="summary-card">
          <template v-if="!editable">
            <div class="summary-head">
              <div>
                <h1 class="summary-title">{{ housing.title }}</h1>
                <p class="summary-location">{{ housing.communityName }} · {{ housing.houseLocation }}</p>
              </div>
              <StatusTag
                :label="housingStatusLabels[housing.status]"
                :type="tagTypeMap[housing.status]"
              />
            </div>

            <div class="summary-price">
              <span class="price-amount">¥{{ formatRent(housing.monthlyRent) }}</span>
              <span class="price-unit">/月</span>
              <span class="price-deposit">
                押金 {{ housing.deposit != null ? `¥${formatRent(housing.deposit)}` : '面议' }}
              </span>
            </div>

            <dl class="summary-meta">
              <div class="meta-item">
                <dt>浏览量</dt>
                <dd>{{ housing.viewCount }} 次</dd>
              </div>
              <div class="meta-item">
                <dt>发布时间</dt>
                <dd>{{ formatDateTime(housing.publishTime) }}</dd>
              </div>
              <div class="meta-item">
                <dt>创建时间</dt>
                <dd>{{ formatDateTime(housing.createdAt) }}</dd>
              </div>
            </dl>

            <div class="summary-actions">
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                :type="housing.status === 'OFFLINE' ? 'success' : 'warning'"
                @click="handleToggleStatus"
              >
                {{ housing.status === 'OFFLINE' ? '上架' : '下架' }}
              </el-button>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="toggleEdit">编辑信息</el-button>
            </div>

            <div class="summary-description">
              <h3>房源描述</h3>
              <p>{{ housing.description || '暂无描述' }}</p>
            </div>
          </template>

          <el-form v-else label-width="80px">
            <el-form-item label="标题">
              <el-input v-model="form.title" maxlength="60" show-word-limit />
            </el-form-item>
            <el-form-item label="月租金">
              <el-input-number v-model="form.monthlyRent" :min="0" :step="100" />
              <span class="form-unit">元/月</span>
            </el-form-item>
            <el-form-item label="押金">
              <el-input-number v-model="form.deposit" :min="0" :step="100" />
              <span class="form-unit">元（不填则面议）</span>
            </el-form-item>
            <el-form-item label="图片">
              <el-input v-model="form.imagesText" type="textarea" :rows="3" placeholder="图片地址，每行一个" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
              <el-button @click="toggleEdit">取消</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div class="bottom-grid">
        <!-- 左：看房时段配置（列表卡片「时段」动作经 #timeslots 锚定到此） -->
        <div id="timeslots" class="panel-card">
          <div class="timeslot-head">
            <h2 class="card-title">看房时段配置</h2>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" size="small" @click="openSlotCreate">＋ 新增时段</el-button>
          </div>
          <el-table v-loading="timeslotLoading" :data="timeslotRecords" stripe size="small">
            <el-table-column label="星期" width="80" align="center">
              <template #default="{ row }">{{ dayOfWeekName(row.dayOfWeek) }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="130">
              <template #default="{ row }">{{ row.startTime }} ~ {{ row.endTime }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <StatusTag
                  :label="row.isAvailable ? '开放' : '停用'"
                  :type="row.isAvailable ? 'completed' : 'canceled'"
                />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openSlotEdit(row)">编辑</el-button>
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleSlotDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 右：关联看房预约（最近 5 条只读；全量处置在看房预约 Tab） -->
        <div class="panel-card">
          <div class="timeslot-head">
            <h2 class="card-title">关联看房预约</h2>
            <el-link type="primary" :underline="'never'" @click="goAllViewings">查看全部</el-link>
          </div>
          <el-table v-loading="relatedLoading" :data="relatedViewings" stripe size="small">
            <el-table-column label="看房时间" min-width="140">
              <template #default="{ row }">{{ row.appointmentDate }} {{ row.startTime }} ~ {{ row.endTime }}</template>
            </el-table-column>
            <el-table-column prop="visitorName" label="看房人" min-width="80" />
            <el-table-column prop="contactPhone" label="联系电话" min-width="110" />
            <el-table-column label="状态" width="86" align="center">
              <template #default="{ row }">
                <StatusTag
                  :label="viewingAppointmentStatusLabels[row.status as ViewingAppointmentStatus]"
                  :type="viewingTagTypeMap[row.status as ViewingAppointmentStatus]"
                />
              </template>
            </el-table-column>
            <template #empty>该房源暂无看房预约</template>
          </el-table>
        </div>
      </div>
    </template>

    <!-- 时段新建/编辑对话框 -->
    <el-dialog
      v-model="slotDialogVisible"
      :title="editingSlotId === null ? '新增看房时段' : `编辑时段 #${editingSlotId}`"
      width="420px"
      destroy-on-close
    >
      <el-form label-width="80px">
        <el-form-item label="星期" required>
          <el-select v-model="slotForm.dayOfWeek" style="width: 180px">
            <el-option v-for="(name, dow) in ['周日','周一','周二','周三','周四','周五','周六']" :key="dow" :label="name" :value="Number(dow)" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始时间" required>
          <el-time-picker v-model="slotForm.startTime" format="HH:mm" value-format="HH:mm" placeholder="如 09:00" style="width: 180px" />
        </el-form-item>
        <el-form-item label="结束时间" required>
          <el-time-picker v-model="slotForm.endTime" format="HH:mm" value-format="HH:mm" placeholder="如 10:00" style="width: 180px" />
        </el-form-item>
        <el-form-item label="开放预约">
          <el-switch v-model="slotForm.isAvailable" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="slotDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="slotSaving" @click="handleSlotSave">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.detail-breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.breadcrumb-separator {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

/* 上部两栏：图集 + 信息摘要 */
.hero-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  align-items: start;
}

.gallery-card,
.summary-card,
.panel-card {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-lg);
}

.gallery {
  position: relative;
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-md);
  overflow: hidden;
  background-color: var(--color-bg);
}

.gallery-main {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.gallery-status {
  position: absolute;
  top: var(--spacing-sm);
  right: var(--spacing-sm);
}

.gallery-thumbs {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
  overflow-x: auto;
}

.gallery-thumb {
  flex-shrink: 0;
  width: 72px;
  height: 54px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: var(--radius-sm);
  overflow: hidden;
  cursor: pointer;
  background: none;
}

.gallery-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.gallery-thumb.active {
  border-color: var(--color-primary);
}

/* 信息摘要 */
.summary-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.summary-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.summary-location {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.summary-price {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin: var(--spacing-md) 0;
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-primary-bg);
}

.price-amount {
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  line-height: var(--line-height-tight);
}

.price-unit {
  font-size: var(--font-size-sm);
  color: var(--color-primary);
}

.price-deposit {
  margin-left: auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.summary-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--spacing-md);
  margin: 0 0 var(--spacing-md);
}

.meta-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.meta-item dd {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.summary-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.summary-description h3 {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.summary-description p {
  margin: 0;
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

/* 下部两栏：时段配置 + 关联看房预约 */
.bottom-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: start;
}

.card-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.timeslot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.form-unit {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

@media (max-width: 1023px) {
  .hero-grid,
  .bottom-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 767px) {
  .summary-meta {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
