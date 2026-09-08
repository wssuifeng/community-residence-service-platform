<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import StatCard from '@/components/common/StatCard.vue'
import {
  getHousingDetail,
  updateHousing,
  updateHousingStatus,
  listHousingTimeslots,
  createHousingTimeslot,
  updateHousingTimeslot,
  deleteHousingTimeslot
} from '@/api/housing'
import type {
  IHousing,
  IHousingTimeslot,
  HousingStatus,
  HousingTimeslotSaveDTO
} from '@/types/modules/housing'
import { housingStatusLabels } from '@/types/modules/housing'
import { formatDate } from '@/utils/date'

/**
 * 房源详情（管理端）：房源信息编辑 + 浏览量 + 看房时段配置管理
 * （时段是居民端看房预约的可选来源，约满自动置灰）
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

/* ------------------------------ 信息编辑 ------------------------------ */

const editable = ref(false)
const saving = ref(false)

const form = reactive({
  title: '',
  description: '',
  monthlyRent: 0,
  depositAmount: undefined as number | undefined,
  availableDate: '',
  contactPerson: '',
  contactPhone: '',
  imagesText: '',
  tagsText: ''
})

function fillForm(source: IHousing): void {
  form.title = source.title
  form.description = source.description
  form.monthlyRent = source.monthlyRent
  form.depositAmount = source.depositAmount ?? undefined
  form.availableDate = source.availableDate
  form.contactPerson = source.contactPerson
  form.contactPhone = source.contactPhone ?? ''
  form.imagesText = source.images.join('\n')
  form.tagsText = (source.tags ?? []).join('、')
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
      communityId: housing.value.communityId,
      houseId: housing.value.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      depositAmount: form.depositAmount,
      availableDate: form.availableDate,
      contactPerson: form.contactPerson.trim(),
      contactPhone: form.contactPhone.trim() || undefined,
      images: form.imagesText
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line.length > 0),
      tags: form.tagsText
        .split(/[、,，\s]+/)
        .map((tag) => tag.trim())
        .filter((tag) => tag.length > 0)
    })
    ElMessage.success('房源信息已保存')
    editable.value = false
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

const timeslotTotal = ref(0)
const timeslotRecords = ref<IHousingTimeslot[]>([])
const timeslotLoading = ref(false)

async function loadTimeslots(): Promise<void> {
  timeslotLoading.value = true
  try {
    const list = await listHousingTimeslots(housingId)
    timeslotRecords.value = list
    timeslotTotal.value = list.length
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

async function handleSlotDelete(row: IHousingTimeslot): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除 周${'日一二三四五六'[row.dayOfWeek]} ${row.startTime} ~ ${row.endTime} 时段？已有预约时无法删除`,
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

const coverImage = computed(() => {
  const images = housing.value?.images ?? []
  return images.length > 0 ? images[0] : `/images/housing-sample-${(housingId % 3) + 1}.png`
})

onMounted(() => {
  loadDetail()
  loadTimeslots()
})
</script>

<template>
  <section v-loading="loading" class="housing-detail-admin">
    <div class="back-row">
      <el-button link @click="router.back()">← 返回房源列表</el-button>
    </div>

    <template v-if="housing">
      <header class="page-head">
        <div class="head-info">
          <h1>{{ housing.title }}</h1>
          <p class="head-sub">
            {{ housing.communityName }} · {{ housing.houseAddress }}
            <StatusTag
              class="head-status"
              :label="housingStatusLabels[housing.status]"
              :type="tagTypeMap[housing.status]"
            />
          </p>
        </div>
        <div class="head-actions">
          <el-button
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            :type="housing.status === 'OFFLINE' ? 'success' : 'warning'"
            @click="handleToggleStatus"
          >
            {{ housing.status === 'OFFLINE' ? '上架' : '下架' }}
          </el-button>
          <el-button v-if="!editable" v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="toggleEdit">编辑信息</el-button>
        </div>
      </header>

      <div class="stats-row">
        <StatCard label="月租金" :value="`￥${housing.monthlyRent}`" unit="/月" type="primary" />
        <StatCard label="浏览量" :value="housing.viewCount" unit="次" />
        <StatCard label="可入住日期" :value="formatDate(housing.availableDate)" />
        <StatCard label="发布时间" :value="formatDate(housing.createdAt)" />
      </div>

      <div class="content-grid">
        <!-- 左：信息编辑 -->
        <div class="info-card">
          <h2 class="card-title">房源信息</h2>
          <template v-if="!editable">
            <div class="view-image">
              <img :src="coverImage" :alt="housing.title" />
            </div>
            <dl class="view-meta">
              <div class="meta-item">
                <dt>押金</dt>
                <dd>{{ housing.depositAmount != null ? `￥${housing.depositAmount}` : '面议' }}</dd>
              </div>
              <div class="meta-item">
                <dt>联系人</dt>
                <dd>{{ housing.contactPerson }}<template v-if="housing.contactPhone">（{{ housing.contactPhone }}）</template></dd>
              </div>
              <div class="meta-item">
                <dt>标签</dt>
                <dd>
                  <template v-if="(housing.tags?.length ?? 0) > 0">
                    <span v-for="tag in housing.tags" :key="tag" class="tag-chip">{{ tag }}</span>
                  </template>
                  <template v-else>—</template>
                </dd>
              </div>
            </dl>
            <div class="view-description">
              <h3>描述</h3>
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
              <el-input-number v-model="form.depositAmount" :min="0" :step="100" />
              <span class="form-unit">元（不填则面议）</span>
            </el-form-item>
            <el-form-item label="可入住">
              <el-date-picker v-model="form.availableDate" type="date" value-format="YYYY-MM-DD" style="width: 200px" />
            </el-form-item>
            <el-form-item label="联系人">
              <el-input v-model="form.contactPerson" maxlength="30" style="width: 200px" />
            </el-form-item>
            <el-form-item label="联系电话">
              <el-input v-model="form.contactPhone" maxlength="20" style="width: 200px" />
            </el-form-item>
            <el-form-item label="图片">
              <el-input v-model="form.imagesText" type="textarea" :rows="3" placeholder="图片地址，每行一个" />
            </el-form-item>
            <el-form-item label="标签">
              <el-input v-model="form.tagsText" placeholder="多个标签用「、」分隔" />
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

        <!-- 右：时段配置 -->
        <div class="timeslot-card">
          <div class="timeslot-head">
            <h2 class="card-title">看房时段配置</h2>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" size="small" @click="openSlotCreate">＋ 新增时段</el-button>
          </div>
          <el-table v-loading="timeslotLoading" :data="timeslotRecords" stripe size="small">
            <el-table-column label="星期" width="80" align="center">
              <template #default="{ row }">{{ '日一二三四五六'[row.dayOfWeek] }}</template>
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
.back-row {
  margin-bottom: var(--spacing-sm);
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: var(--spacing-md);
  align-items: start;
}

.info-card,
.timeslot-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
}

.card-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-md);
}

.view-image {
  aspect-ratio: 4 / 3;
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: var(--spacing-md);
  background-color: var(--color-bg);
}

.view-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.view-meta {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.meta-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.meta-item dd {
  font-size: var(--font-size-sm);
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.tag-chip {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
}

.view-description h3 {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  margin-bottom: var(--spacing-sm);
}

.view-description p {
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
}

.timeslot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-unit {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

@media (max-width: 1023px) {
  .content-grid {
    grid-template-columns: 1fr;
  }
}
</style>
