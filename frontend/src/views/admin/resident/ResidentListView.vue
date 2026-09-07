<script setup lang="ts">
/** 居民列表：关键词/状态筛选 + 冻结/解冻（接口设计.md 9.2.1.8 / 9.2.1.9） */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getResidentList, updateResidentStatus } from '@/api/resident'
import type { IResident, ResidentStatus } from '@/types/modules/resident'
import { residentStatusLabels } from '@/types/modules/resident'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

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

onMounted(load)
</script>

<template>
  <section class="resident-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索用户名/姓名/手机号" @search="handleSearch" />
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">状态</span>
      <el-select v-model="statusFilter" style="width: 120px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in residentStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="records" border>
      <el-table-column prop="username" label="用户名" min-width="120" show-overflow-tooltip />
      <el-table-column prop="realName" label="姓名" min-width="100" show-overflow-tooltip />
      <el-table-column label="手机号" width="130">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag
            :label="residentStatusLabels[row.status as ResidentStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="goDetail(row)">详情</el-button>
          <el-button
            link
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            size="small"
            @click="handleToggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '冻结' : '解冻' }}
          </el-button>
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

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
