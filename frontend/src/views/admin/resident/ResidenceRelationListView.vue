<script setup lang="ts">
/**
 * 居住关系台账：按居民查询居住关系（接口仅提供居民/房屋两视角列表，
 * 台账采用居民视角 9.2.3.1）+ 在住关系搬出登记（9.2.3.3）
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  getResidentList,
  getResidentResidenceList,
  moveOutResidenceRelation
} from '@/api/resident'
import type {
  IResident,
  IResidenceRelation,
  ResidenceRelationStatus
} from '@/types/modules/resident'
import { residenceRelationStatusLabels } from '@/types/modules/resident'
import { formatDate, todayISO } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

const relations = ref<IResidenceRelation[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<ResidenceRelationStatus | ''>('')

/* 居民选择器（远程搜索，接口无全量居住关系列表，必须先定位居民） */
const residentId = ref<number | undefined>(undefined)
const residentOptions = ref<IResident[]>([])
const residentLoading = ref(false)

/** 居住关系状态 → StatusTag 语义色（居住中=绿 / 已迁出=灰） */
function statusTagType(status: ResidenceRelationStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function load(): Promise<void> {
  if (!residentId.value) {
    relations.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await getResidentResidenceList(residentId.value, {
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined
    })
    relations.value = result.records
    total.value = result.total
  } catch {
    relations.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function searchResidents(keyword: string): Promise<void> {
  residentLoading.value = true
  try {
    const result = await getResidentList({ page: 1, size: 20, keyword: keyword || undefined })
    residentOptions.value = result.records
  } catch {
    residentOptions.value = []
  } finally {
    residentLoading.value = false
  }
}

function handleResidentChange(): void {
  page.value = 1
  load()
}

function handleStatusChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  residentId.value = undefined
  statusFilter.value = ''
  page.value = 1
  relations.value = []
  total.value = 0
}

/* 搬出登记：必须填写搬出日期 */
const moveOutVisible = ref(false)
const moveOutLoading = ref(false)
const moveOutFormRef = ref<FormInstance>()
const moveOutTarget = ref<IResidenceRelation | null>(null)
const moveOutForm = reactive<{ moveOutDate: string; reason: string }>({
  moveOutDate: '',
  reason: ''
})

const moveOutRules: FormRules = {
  moveOutDate: [{ required: true, message: '请选择搬出日期', trigger: 'change' }],
  reason: [{ max: 200, message: '搬出原因不超过 200 字', trigger: 'blur' }]
}

function openMoveOut(row: IResidenceRelation): void {
  moveOutTarget.value = row
  moveOutFormRef.value?.resetFields()
  Object.assign(moveOutForm, { moveOutDate: todayISO(), reason: '' })
  moveOutVisible.value = true
}

async function handleMoveOut(): Promise<void> {
  if (!moveOutTarget.value) return
  const valid = await moveOutFormRef.value?.validate().catch(() => false)
  if (!valid) return
  moveOutLoading.value = true
  try {
    await moveOutResidenceRelation(moveOutTarget.value.id, {
      moveOutDate: moveOutForm.moveOutDate,
      reason: moveOutForm.reason.trim() || undefined
    })
    ElMessage.success('搬出登记成功')
    moveOutVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '搬出登记失败')
  } finally {
    moveOutLoading.value = false
  }
}

onMounted(() => {
  searchResidents('')
})
</script>

<template>
  <section class="relation-list">
    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">居民</span>
      <el-select
        v-model="residentId"
        filterable
        remote
        clearable
        :remote-method="searchResidents"
        :loading="residentLoading"
        placeholder="搜索并选择居民（用户名/姓名/手机号）"
        style="width: 260px"
        @change="handleResidentChange"
        @focus="searchResidents('')"
      >
        <el-option
          v-for="item in residentOptions"
          :key="item.id"
          :label="`${item.realName}（${item.username}）`"
          :value="item.id"
        />
      </el-select>
      <span class="filter-label">状态</span>
      <el-select
        v-model="statusFilter"
        :disabled="!residentId"
        style="width: 120px"
        @change="handleStatusChange"
      >
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in residenceRelationStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table
      v-loading="loading"
      :data="relations"
      :empty-text="residentId ? '暂无居住关系' : '请先在上方选择居民'"
      border
    >
      <el-table-column prop="residentName" label="居民" min-width="100" show-overflow-tooltip />
      <el-table-column prop="houseLocation" label="房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="入住日期" width="120">
        <template #default="{ row }">{{ formatDate(row.moveInDate) }}</template>
      </el-table-column>
      <el-table-column label="搬出日期" width="120">
        <template #default="{ row }">{{ formatDate(row.moveOutDate) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag
            :label="residenceRelationStatusLabels[row.status as ResidenceRelationStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'ACTIVE'"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            type="warning"
            size="small"
            @click="openMoveOut(row)"
          >
            搬出登记
          </el-button>
          <span v-else class="op-done">-</span>
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

    <!-- 搬出登记 -->
    <el-dialog v-model="moveOutVisible" title="搬出登记" width="480px">
      <el-alert
        v-if="moveOutTarget"
        :title="`登记后「${moveOutTarget.residentName}」与「${moveOutTarget.houseLocation}」的居住关系将结束，关联租住记录同步终止`"
        type="info"
        :closable="false"
        class="move-out-tip"
      />
      <el-form ref="moveOutFormRef" :model="moveOutForm" :rules="moveOutRules" label-width="90px">
        <el-form-item label="搬出日期" prop="moveOutDate">
          <el-date-picker
            v-model="moveOutForm.moveOutDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择搬出日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="搬出原因" prop="reason">
          <el-input
            v-model="moveOutForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="选填，如：租约到期"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveOutVisible = false">取消</el-button>
        <el-button type="primary" :loading="moveOutLoading" @click="handleMoveOut">
          确认登记
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.op-done {
  color: var(--color-text-disabled);
}

.move-out-tip {
  margin-bottom: var(--spacing-md);
}
</style>
