<script setup lang="ts">
/** 居民列表 Tab：关键词/状态筛选 + 详情/居住关系/冻结解冻（接口设计.md 9.2.1.8 / 9.2.1.9） */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getResidentList, updateResidentStatus } from '@/api/resident'
import type { IResident, ResidentStatus } from '@/types/modules/resident'
import { residentStatusLabels } from '@/types/modules/resident'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

const emit = defineEmits<{ 'go-relations': [residentId: number] }>()

const router = useRouter()

const records = ref<IResident[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref<ResidentStatus | ''>('')

/** 账号状态 → StatusTag 语义色（正常=绿 / 已冻结=红） */
function statusTagType(status: ResidentStatus): 'completed' | 'rejected' {
  return status === 'ACTIVE' ? 'completed' : 'rejected'
}

/* 头像底色：按居民 ID 稳定取模映射语义 soft 色对（后端无头像字段，姓名首字替代） */
const AVATAR_ACCENTS = ['is-primary', 'is-success', 'is-warning', 'is-danger'] as const

function avatarClass(id: number): string {
  return AVATAR_ACCENTS[id % AVATAR_ACCENTS.length]
}

function avatarChar(row: IResident): string {
  return row.realName?.trim()?.charAt(0) || row.username.charAt(0)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getResidentList({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined
    })
    records.value = result.records
    total.value = result.total
  } catch {
    records.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  keyword.value = ''
  statusFilter.value = ''
  page.value = 1
  load()
}

/** 冻结：需填写原因；解冻：直接确认（IUpdateResidentStatusDTO.reason 可选） */
async function handleToggleStatus(row: IResident): Promise<void> {
  if (row.status === 'ACTIVE') {
    let reason: string
    try {
      const result = await ElMessageBox.prompt(
        '请输入冻结原因，将通知居民并记录留痕',
        `冻结账号「${row.username}」`,
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
      await updateResidentStatus(row.id, { status: 'FROZEN', reason })
      ElMessage.success('账号已冻结')
      load()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '冻结失败')
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定解冻账号「${row.username}」？解冻后该居民可正常登录。`,
      '解冻账号',
      { type: 'info', confirmButtonText: '解冻', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateResidentStatus(row.id, { status: 'ACTIVE' })
    ElMessage.success('账号已解冻')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解冻失败')
  }
}

function goDetail(row: IResident): void {
  router.push(`/admin/residents/${row.id}`)
}

function goRelations(row: IResident): void {
  emit('go-relations', row.id)
}

onMounted(load)
</script>

<template>
  <section class="resident-list">
    <FilterPanel resettable @reset="handleReset">
      <el-input
        v-model="keyword"
        clearable
        class="keyword-input"
        placeholder="搜索姓名/手机号/用户名"
        @keydown.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #prefix>
          <svg class="input-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M21 21l-4.35-4.35M17 10.5a6.5 6.5 0 1 1-13 0 6.5 6.5 0 0 1 13 0z"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
          </svg>
        </template>
      </el-input>
      <el-select
        v-model="statusFilter"
        clearable
        placeholder="全部状态"
        class="status-select"
        @change="handleSearch"
      >
        <el-option
          v-for="(label, value) in residentStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <el-button type="primary" plain @click="handleSearch">查询</el-button>
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="records">
        <el-table-column label="居民" min-width="200">
          <template #default="{ row }">
            <div class="resident-cell">
              <span class="resident-avatar" :class="avatarClass(row.id)" aria-hidden="true">
                {{ avatarChar(row) }}
              </span>
              <div class="resident-cell-text">
                <span class="resident-name">{{ row.realName }}</span>
                <span class="resident-username">{{ row.username }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="手机号" width="140">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="身份证号（脱敏）" width="190">
          <template #default="{ row }">{{ row.idCardMasked || '—' }}</template>
        </el-table-column>
        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="residentStatusLabels[row.status as ResidentStatus]"
              :type="statusTagType(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="goDetail(row)">详情</el-button>
            <el-button text type="primary" size="small" @click="goRelations(row)">居住关系</el-button>
            <el-button
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
              size="small"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 'ACTIVE' ? '冻结' : '解冻' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无符合条件的居民" :image-size="80" />
        </template>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="load"
        @update:size="load"
      />
    </div>
  </section>
</template>

<style scoped>
.keyword-input {
  width: 260px;
}

.input-icon {
  width: 14px;
  height: 14px;
  vertical-align: -2px;
  color: var(--color-text-secondary);
}

.status-select {
  width: 130px;
}

/* 表格白卡容器（含分页，与工单管理 table-panel 同款） */
.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

/* 居民单元格：姓名首字头像 + 姓名 + 用户名次行（后端无头像/住址字段） */
.resident-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.resident-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-circle);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.resident-avatar.is-primary {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.resident-avatar.is-success {
  color: var(--color-success);
  background-color: var(--color-success-soft);
}

.resident-avatar.is-warning {
  color: var(--color-warning);
  background-color: var(--color-warning-soft);
}

.resident-avatar.is-danger {
  color: var(--color-danger);
  background-color: var(--color-danger-soft);
}

.resident-cell-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.resident-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
}

.resident-username {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
