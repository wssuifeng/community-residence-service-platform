<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createResource,
  createTimeslot,
  deleteResource,
  deleteTimeslot,
  getCommunityList,
  getResourceList,
  getTimeslotList,
  updateResource
} from '@/api/community'
import type {
  ICommunity,
  ICreateTimeslotDTO,
  IPublicResource,
  IPublicResourceDTO,
  IPublicResourceQuery,
  IResourceTimeslot,
  ResourceType,
  TimeslotStatus
} from '@/types/modules/community'
import {
  bookingUnitLabels,
  resourceStatusLabels,
  resourceTypeLabels,
  timeslotStatusLabels
} from '@/types/modules/community'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 公共资源管理：社区筛选 + 表格 CRUD + 可预约时段配置（列出/新增/删除） */
const resources = ref<IPublicResource[]>([])
const communities = ref<ICommunity[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const communityFilter = ref<number | ''>('')
const typeFilter = ref<ResourceType | ''>('')

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch {
    communities.value = []
  }
}

async function load(): Promise<void> {
  if (communityFilter.value === '') {
    resources.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const params: IPublicResourceQuery = {
      page: page.value,
      size: size.value,
      type: typeFilter.value || undefined
    }
    const result = await getResourceList(communityFilter.value, params)
    resources.value = result.records
    total.value = result.total
  } catch {
    resources.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleCommunityChange(): void {
  page.value = 1
  load()
}

function handleTypeChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  communityFilter.value = ''
  typeFilter.value = ''
  page.value = 1
  load()
}

/** 开放时段展示：HH:mm ~ HH:mm */
function openRangeText(row: IPublicResource): string {
  if (!row.openTime && !row.closeTime) return '-'
  return `${row.openTime ?? '--'} ~ ${row.closeTime ?? '--'}`
}

/* ---------------------------------- 新建/编辑 ---------------------------------- */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

/** 表单态 DTO：社区外键未选择时为 undefined（提交前经 rules 校验收敛） */
type ResourceForm = Omit<IPublicResourceDTO, 'communityId'> & { communityId?: number }

const form = reactive<ResourceForm>({
  communityId: undefined,
  name: '',
  type: 'SPORTS',
  location: '',
  capacity: undefined,
  openTime: '',
  closeTime: '',
  bookingUnit: 'HOURLY',
  advanceBookingDays: undefined,
  description: '',
  rules: ''
})

const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  name: [{ required: true, message: '请输入资源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择资源类型', trigger: 'change' }]
}

function openCreate(): void {
  editingId.value = null
  form.communityId = communityFilter.value === '' ? undefined : communityFilter.value
  form.name = ''
  form.type = 'SPORTS'
  form.location = ''
  form.capacity = undefined
  form.openTime = ''
  form.closeTime = ''
  form.bookingUnit = 'HOURLY'
  form.advanceBookingDays = undefined
  form.description = ''
  form.rules = ''
  dialogVisible.value = true
}

function openEdit(row: IPublicResource): void {
  editingId.value = row.id
  form.communityId = row.communityId
  form.name = row.name
  form.type = row.type
  form.location = row.location ?? ''
  form.capacity = row.capacity ?? undefined
  form.openTime = row.openTime ?? ''
  form.closeTime = row.closeTime ?? ''
  form.bookingUnit = row.bookingUnit ?? 'HOURLY'
  form.advanceBookingDays = row.advanceBookingDays ?? undefined
  form.description = row.description ?? ''
  form.rules = row.rules ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    const payload: IPublicResourceDTO = {
      ...form,
      communityId: form.communityId as number,
      capacity: form.capacity ?? undefined,
      advanceBookingDays: form.advanceBookingDays ?? undefined
    }
    if (editingId.value === null) {
      await createResource(payload)
      ElMessage.success('公共资源创建成功')
    } else {
      await updateResource(editingId.value, payload)
      ElMessage.success('公共资源已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 时段配置 ---------------------------------- */

const timeslotVisible = ref(false)
const timeslotLoading = ref(false)
const timeslotResource = ref<IPublicResource | null>(null)
const timeslots = ref<IResourceTimeslot[]>([])
const timeslotPage = ref(1)
const timeslotSize = ref(10)
const timeslotTotal = ref(0)

const timeslotFormRef = ref<FormInstance>()
const timeslotForm = reactive<ICreateTimeslotDTO>({
  date: '',
  startTime: '',
  endTime: '',
  maxBookings: 1
})

const timeslotRules: FormRules = {
  date: [{ required: true, message: '请选择日期', trigger: 'change' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }],
  maxBookings: [{ required: true, message: '请输入最大预约数', trigger: 'blur' }]
}

async function loadTimeslots(): Promise<void> {
  if (!timeslotResource.value) return
  timeslotLoading.value = true
  try {
    const result = await getTimeslotList(timeslotResource.value.id, {
      page: timeslotPage.value,
      size: timeslotSize.value
    })
    timeslots.value = result.records
    timeslotTotal.value = result.total
  } catch {
    timeslots.value = []
    timeslotTotal.value = 0
  } finally {
    timeslotLoading.value = false
  }
}

function openTimeslot(row: IPublicResource): void {
  timeslotResource.value = row
  timeslotPage.value = 1
  timeslotForm.date = ''
  timeslotForm.startTime = ''
  timeslotForm.endTime = ''
  timeslotForm.maxBookings = 1
  timeslotVisible.value = true
  loadTimeslots()
}

async function handleAddTimeslot(): Promise<void> {
  const valid = await timeslotFormRef.value?.validate().catch(() => false)
  if (!valid || !timeslotResource.value) return
  try {
    await createTimeslot(timeslotResource.value.id, { ...timeslotForm })
    ElMessage.success('时段已添加')
    timeslotForm.date = ''
    timeslotForm.startTime = ''
    timeslotForm.endTime = ''
    timeslotForm.maxBookings = 1
    loadTimeslots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '添加时段失败')
  }
}

async function handleDeleteTimeslot(row: IResourceTimeslot): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除时段「${row.date} ${row.startTime}~${row.endTime}」？`,
      '删除时段',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteTimeslot(row.id)
    ElMessage.success('时段已删除')
    loadTimeslots()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除时段失败')
  }
}

/* ---------------------------------- 删除资源（引用保护由后端报错） ---------------------------------- */

async function handleDelete(row: IPublicResource): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除公共资源「${row.name}」？若存在关联时段/预约记录，删除将被拒绝。`,
      '删除公共资源',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteResource(row.id)
    ElMessage.success('公共资源已删除')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  loadCommunities()
})
</script>

<template>
  <section class="resource-list">
    <div class="list-toolbar">
      <span class="toolbar-title">公共资源管理</span>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" :disabled="communityFilter === ''" @click="openCreate">新建资源</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">所属社区</span>
      <el-select
        v-model="communityFilter"
        placeholder="请选择社区"
        style="width: 200px"
        @change="handleCommunityChange"
      >
        <el-option label="全部社区" value="" />
        <el-option
          v-for="item in communities"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <span class="filter-label">资源类型</span>
      <el-select
        v-model="typeFilter"
        placeholder="全部类型"
        style="width: 140px"
        @change="handleTypeChange"
      >
        <el-option label="全部类型" value="" />
        <el-option
          v-for="(label, value) in resourceTypeLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-alert
      v-if="communityFilter === ''"
      title="请先选择社区查看公共资源"
      type="info"
      :closable="false"
      class="empty-hint"
    />
    <template v-else>
      <el-table v-loading="loading" :data="resources" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="name" label="资源名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="communityName" label="所属社区" min-width="130" show-overflow-tooltip />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ resourceTypeLabels[row.type as ResourceType] }}</template>
        </el-table-column>
        <el-table-column prop="location" label="位置" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.location || '-' }}</template>
        </el-table-column>
        <el-table-column label="容量" width="70">
          <template #default="{ row }">{{ row.capacity ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="开放时段" width="150">
          <template #default="{ row }">{{ openRangeText(row) }}</template>
        </el-table-column>
        <el-table-column label="预约单位" width="90">
          <template #default="{ row }">
            {{ row.bookingUnit ? bookingUnitLabels[row.bookingUnit as keyof typeof bookingUnitLabels] : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="提前天数" width="90">
          <template #default="{ row }">{{ row.advanceBookingDays ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag :label="resourceStatusLabels[row.status as keyof typeof resourceStatusLabels]" type="completed" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="warning" size="small" @click="openTimeslot(row)">时段配置</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="load"
        @update:size="load"
      />
    </template>

    <!-- 新建/编辑公共资源 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建公共资源' : '编辑公共资源'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属社区" prop="communityId">
          <el-select v-model="form.communityId" placeholder="请选择社区" style="width: 100%">
            <el-option
              v-for="item in communities"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="资源名称" prop="name">
          <el-input v-model="form.name" placeholder="如：篮球场" maxlength="30" />
        </el-form-item>
        <el-form-item label="资源类型" prop="type">
          <el-select v-model="form.type" style="width: 100%">
            <el-option
              v-for="(label, value) in resourceTypeLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="位置">
          <el-input v-model="form.location" placeholder="请输入资源位置" maxlength="50" />
        </el-form-item>
        <el-form-item label="容量">
          <el-input-number v-model="form.capacity" :min="1" :max="9999" />
        </el-form-item>
        <el-form-item label="开放时间">
          <div class="time-range">
            <el-time-select
              v-model="form.openTime"
              start="00:00"
              end="23:30"
              step="00:30"
              placeholder="开始"
            />
            <span class="time-sep">至</span>
            <el-time-select
              v-model="form.closeTime"
              :start="form.openTime || '00:00'"
              end="23:30"
              step="00:30"
              placeholder="结束"
            />
          </div>
        </el-form-item>
        <el-form-item label="预约单位">
          <el-select v-model="form.bookingUnit" style="width: 100%">
            <el-option
              v-for="(label, value) in bookingUnitLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="提前预约天数">
          <el-input-number v-model="form.advanceBookingDays" :min="0" :max="90" />
        </el-form-item>
        <el-form-item label="资源描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="2"
            placeholder="请输入资源描述"
            maxlength="200"
          />
        </el-form-item>
        <el-form-item label="使用规则">
          <el-input
            v-model="form.rules"
            type="textarea"
            :rows="2"
            placeholder="请输入使用规则"
            maxlength="500"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 可预约时段配置 -->
    <el-dialog
      v-model="timeslotVisible"
      :title="timeslotResource ? `时段配置：${timeslotResource.name}` : '时段配置'"
      width="640px"
    >
      <el-form
        ref="timeslotFormRef"
        :model="timeslotForm"
        :rules="timeslotRules"
        label-width="100px"
        class="timeslot-form"
      >
        <el-form-item label="日期" prop="date">
          <el-date-picker
            v-model="timeslotForm.date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            style="width: 160px"
          />
        </el-form-item>
        <el-form-item label="时间段" prop="startTime">
          <div class="time-range">
            <el-time-select
              v-model="timeslotForm.startTime"
              start="06:00"
              end="22:30"
              step="00:30"
              placeholder="开始"
            />
            <span class="time-sep">至</span>
            <el-time-select
              v-model="timeslotForm.endTime"
              :start="timeslotForm.startTime || '06:00'"
              end="23:00"
              step="00:30"
              placeholder="结束"
            />
          </div>
        </el-form-item>
        <el-form-item label="最大预约数" prop="maxBookings">
          <el-input-number v-model="timeslotForm.maxBookings" :min="1" :max="999" />
        </el-form-item>
        <el-form-item>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="handleAddTimeslot">添加时段</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="timeslotLoading" :data="timeslots" border max-height="320">
        <el-table-column prop="date" label="日期" width="110" />
        <el-table-column label="时间段" width="150">
          <template #default="{ row }">{{ row.startTime }} ~ {{ row.endTime }}</template>
        </el-table-column>
        <el-table-column prop="maxBookings" label="可约数" width="80" />
        <el-table-column prop="currentBookings" label="已约数" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="timeslotStatusLabels[row.status as TimeslotStatus]"
              type="completed"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleDeleteTimeslot(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <Pagination
        v-model:page="timeslotPage"
        v-model:size="timeslotSize"
        :total="timeslotTotal"
        @update:page="loadTimeslots"
        @update:size="loadTimeslots"
      />
    </el-dialog>
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.toolbar-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.empty-hint {
  margin-bottom: var(--spacing-md);
}

.timeslot-form {
  padding: var(--spacing-sm) var(--spacing-md) 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  margin-bottom: var(--spacing-md);
}

.time-range {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.time-sep {
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}
</style>
