<script setup lang="ts">
/** 居民详情：信息头卡 + 基本信息分组 + 居住关系列表（管理员视角）+ 冻结/解冻（接口设计.md 9.2.1.7 / 9.2.1.9 / 9.2.3.1） */
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

/* 头像底色：与居民列表同款语义 soft 色对取模（后端无头像字段，姓名首字替代） */
const AVATAR_ACCENTS = ['is-primary', 'is-success', 'is-warning', 'is-danger'] as const

function avatarClass(id: number): string {
  return AVATAR_ACCENTS[id % AVATAR_ACCENTS.length]
}

function avatarChar(residentValue: IResident): string {
  return residentValue.realName?.trim()?.charAt(0) || residentValue.username.charAt(0)
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
  <section v-loading="detailLoading" class="resident-detail">
    <!-- 面包屑：居民管理 / 姓名（「居民管理」即返回入口） -->
    <nav class="detail-breadcrumb" aria-label="面包屑">
      <el-link type="primary" :underline="'never'" @click="goBack">居民管理</el-link>
      <span class="breadcrumb-separator" aria-hidden="true">/</span>
      <span class="breadcrumb-current">{{ resident?.realName || '居民详情' }}</span>
    </nav>

    <!-- 信息头卡：姓名首字头像 + 姓名/用户名 + 状态 + 冻结/解冻 -->
    <div v-if="resident" class="profile-card">
      <div class="profile-main">
        <span class="profile-avatar" :class="avatarClass(resident.id)" aria-hidden="true">
          {{ avatarChar(resident) }}
        </span>
        <div class="profile-text">
          <div class="profile-name-row">
            <h2 class="profile-name">{{ resident.realName }}</h2>
            <StatusTag
              :label="residentStatusLabels[resident.status]"
              :type="accountTagType(resident.status)"
            />
          </div>
          <p class="profile-username">用户名：{{ resident.username }}</p>
        </div>
      </div>
      <div class="profile-actions">
        <el-button
          :type="resident.status === 'ACTIVE' ? 'danger' : 'success'"
          @click="handleToggleStatus"
        >
          {{ resident.status === 'ACTIVE' ? '冻结账号' : '解冻账号' }}
        </el-button>
      </div>
    </div>

    <!-- 基本信息分组白卡（后端 ResidentVO 实际字段：身份证号为脱敏 idCardMasked） -->
    <el-card v-if="resident" shadow="never" class="info-card">
      <template #header>
        <span class="panel-title">基本信息</span>
      </template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="用户名">{{ resident.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ resident.realName }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ resident.phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ resident.email || '—' }}</el-descriptions-item>
        <el-descriptions-item label="身份证号（脱敏）">
          {{ resident.idCardMasked || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">
          {{ formatDateTime(resident.createdAt) }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 关联数据：居住关系 -->
    <el-card shadow="never" class="info-card">
      <template #header>
        <span class="panel-title">居住关系</span>
      </template>
      <div class="relation-filter">
        <el-select
          v-model="relationStatusFilter"
          clearable
          placeholder="全部状态"
          class="status-select"
          @change="handleRelationStatusChange"
        >
          <el-option
            v-for="(label, value) in residenceRelationStatusLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <el-button text @click="handleRelationReset">重置</el-button>
      </div>

      <el-table v-loading="relationLoading" :data="relations">
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
              :type="relationTagType(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="建档时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无居住关系" :image-size="80" />
        </template>
      </el-table>

      <Pagination
        v-model:page="relationPage"
        v-model:size="relationSize"
        :total="relationTotal"
        @update:page="loadRelations"
        @update:size="loadRelations"
      />
    </el-card>
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

/* 信息头卡 */
.profile-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.profile-main {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  min-width: 0;
}

.profile-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  border-radius: var(--radius-circle);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-medium);
}

.profile-avatar.is-primary {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.profile-avatar.is-success {
  color: var(--color-success);
  background-color: var(--color-success-soft);
}

.profile-avatar.is-warning {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.profile-avatar.is-danger {
  color: var(--color-danger);
  background-color: var(--color-danger-soft);
}

.profile-text {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 0;
}

.profile-name-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.profile-name {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.profile-username {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.profile-actions {
  flex-shrink: 0;
}

/* 信息分组白卡 */
.info-card {
  margin-bottom: var(--spacing-lg);
  border: none;
  box-shadow: var(--shadow-card);
  border-radius: var(--radius-lg);
}

.info-card :deep(.el-card__header) {
  padding: var(--spacing-md) var(--spacing-lg);
  border-bottom: 1px solid var(--color-border);
}

.info-card :deep(.el-card__body) {
  padding: var(--spacing-lg);
}

.panel-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.status-select {
  width: 130px;
}

.relation-filter {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

@media (max-width: 767px) {
  .profile-card {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
