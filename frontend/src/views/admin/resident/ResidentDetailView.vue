<script setup lang="ts">
/** 居民详情：个人信息 + 居住关系列表（管理员视角）+ 冻结/解冻（接口设计.md 9.2.1.7 / 9.2.1.9 / 9.2.3.1） */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getResident,
  getResidentResidenceList,
  updateResidentStatus
} from '@/api/resident'
import type {
  IResident,
  IResidenceRelation,
  ResidenceRelationStatus,
  ResidentStatus
} from '@/types/modules/resident'
import { residentStatusLabels, residenceRelationStatusLabels } from '@/types/modules/resident'
import { formatDate, formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

const route = useRoute()
const router = useRouter()

const residentId = computed(() => Number(route.params.id))

const resident = ref<IResident | null>(null)
const detailLoading = ref(false)

const relations = ref<IResidenceRelation[]>([])
const relationLoading = ref(false)
const relationPage = ref(1)
const relationSize = ref(10)
const relationTotal = ref(0)
const relationStatusFilter = ref<ResidenceRelationStatus | ''>('')

/** 账号状态 → StatusTag 语义色（正常=绿 / 已冻结=红） */
function accountTagType(status: ResidentStatus): 'completed' | 'rejected' {
  return status === 'ACTIVE' ? 'completed' : 'rejected'
}

/** 居住关系状态 → StatusTag 语义色（居住中=绿 / 已迁出=灰） */
function relationTagType(status: ResidenceRelationStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function loadDetail(): Promise<void> {
  detailLoading.value = true
  try {
    resident.value = await getResident(residentId.value)
  } catch {
    resident.value = null
  } finally {
    detailLoading.value = false
  }
}

async function loadRelations(): Promise<void> {
  relationLoading.value = true
  try {
    const result = await getResidentResidenceList(residentId.value, {
      page: relationPage.value,
      size: relationSize.value,
      status: relationStatusFilter.value || undefined
    })
    relations.value = result.records
    relationTotal.value = result.total
  } catch {
    relations.value = []
    relationTotal.value = 0
  } finally {
    relationLoading.value = false
  }
}

function handleRelationStatusChange(): void {
  relationPage.value = 1
  loadRelations()
}

function handleRelationReset(): void {
  relationStatusFilter.value = ''
  relationPage.value = 1
  loadRelations()
}

/** 冻结：需填写原因；解冻：直接确认（IUpdateResidentStatusDTO.reason 可选） */
async function handleToggleStatus(): Promise<void> {
  if (!resident.value) return
  const { id, username, status } = resident.value
  if (status === 'ACTIVE') {
    let reason: string
    try {
      const result = await ElMessageBox.prompt(
        '请输入冻结原因，将通知居民并记录留痕',
        `冻结账号「${username}」`,
        {
          type: 'warning',
          confirmButtonText: '冻结',
          cancelButtonText: '取消',
          inputPlaceholder: '冻结原因（必填）',
          inputValidator: (value: string) =>
            value.trim().length > 0 ? true : '冻结原因不能为空'
        }
      )
      reason = result.value.trim()
    } catch {
      return
    }
    try {
      await updateResidentStatus(id, { status: 'FROZEN', reason })
      ElMessage.success('账号已冻结')
      loadDetail()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '冻结失败')
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定解冻账号「${username}」？解冻后该居民可正常登录。`,
      '解冻账号',
      { type: 'info', confirmButtonText: '解冻', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateResidentStatus(id, { status: 'ACTIVE' })
    ElMessage.success('账号已解冻')
    loadDetail()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解冻失败')
  }
}

function goBack(): void {
  router.push('/admin/residents')
}

onMounted(() => {
  loadDetail()
  loadRelations()
})
</script>

<template>
  <section class="resident-detail">
    <div class="detail-toolbar">
      <el-button @click="goBack">返回列表</el-button>
      <el-button
        v-if="resident"
        :type="resident.status === 'ACTIVE' ? 'danger' : 'success'"
        @click="handleToggleStatus"
      >
        {{ resident.status === 'ACTIVE' ? '冻结账号' : '解冻账号' }}
      </el-button>
    </div>

    <el-card v-loading="detailLoading" shadow="never" class="detail-card">
      <template #header>
        <div class="card-header">
          <span>个人信息</span>
          <StatusTag
            v-if="resident"
            :label="residentStatusLabels[resident.status]"
            :type="accountTagType(resident.status)"
          />
        </div>
      </template>
      <el-descriptions v-if="resident" :column="3" border>
        <el-descriptions-item label="用户名">{{ resident.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ resident.realName }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ resident.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ resident.email || '-' }}</el-descriptions-item>
        <el-descriptions-item label="身份证号">{{ resident.idCardNumber || '-' }}</el-descriptions-item>
        <el-descriptions-item label="违约次数">{{ resident.violationCount ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="注册时间" :span="3">
          {{ formatDateTime(resident.createdAt) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <div class="relation-header">居住关系</div>

    <FilterPanel resettable @reset="handleRelationReset">
      <span class="filter-label">状态</span>
      <el-select
        v-model="relationStatusFilter"
        style="width: 120px"
        @change="handleRelationStatusChange"
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

    <el-table v-loading="relationLoading" :data="relations" border>
      <el-table-column prop="houseAddress" label="房屋" min-width="180" show-overflow-tooltip />
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
            :type="relationTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="建档时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="relationPage"
      v-model:size="relationSize"
      :total="relationTotal"
      @update:page="loadRelations"
      @update:size="loadRelations"
    />
  </section>
</template>

<style scoped>
.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
}

.detail-card {
  margin-bottom: var(--spacing-lg);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.relation-header {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-md);
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
