<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getOperationLogList, getOperationLogDetail } from '@/api/operationlog'
import type { IOperationLog, IOperationLogDetail } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 操作日志：多维筛选 + 分页列表 + 行点击详情（含 requestParams JSON 展示） */

/** 操作人类型中文标签 */
const operatorTypeLabels: Record<string, string> = {
  ADMIN: '管理员',
  STAFF: '服务人员',
  RESIDENT: '居民'
}

/** 常见模块中文标签（后端 module 为英文标识，仅做展示翻译，未知值原样展示） */
const moduleLabels: Record<string, string> = {
  AUTH: '认证',
  COMMUNITY: '社区',
  RESIDENT: '居民',
  LEASE: '租住',
  WORK_ORDER: '工单',
  NOTICE: '公告',
  FEEDBACK: '反馈',
  RESERVATION: '资源预约',
  EVALUATION: '评价',
  STATISTICS: '统计',
  SYS_USER: '系统用户',
  NOTIFICATION: '通知',
  HOUSING: '房源'
}

const logs = ref<IOperationLog[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const operatorTypeFilter = ref<string>('')
const moduleFilter = ref('')
const actionFilter = ref('')
/** 时间范围 [start, end]，YYYY-MM-DD */
const dateRange = ref<[string, string] | null>(null)

/** 详情对话框 */
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<IOperationLogDetail | null>(null)

/** requestParams JSON 美化展示 */
function formatRequestParams(params: string | undefined): string {
  if (!params) return '-'
  try {
    return JSON.stringify(JSON.parse(params), null, 2)
  } catch {
    return params
  }
}

function moduleLabel(module: string): string {
  return moduleLabels[module] ?? module
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getOperationLogList({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      operatorType: (operatorTypeFilter.value || undefined) as IOperationLog['operatorType'],
      module: moduleFilter.value.trim() || undefined,
      action: actionFilter.value.trim() || undefined,
      startTime: dateRange.value?.[0] ? `${dateRange.value[0]} 00:00:00` : undefined,
      endTime: dateRange.value?.[1] ? `${dateRange.value[1]} 23:59:59` : undefined
    })
    logs.value = result.records
    total.value = result.total
  } catch {
    logs.value = []
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
  operatorTypeFilter.value = ''
  moduleFilter.value = ''
  actionFilter.value = ''
  dateRange.value = null
  page.value = 1
  load()
}

async function openDetail(row: IOperationLog): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getOperationLogDetail(row.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '日志详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="operation-log-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索操作人/描述" @search="handleSearch" />
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">操作人类型</span>
      <el-select v-model="operatorTypeFilter" style="width: 130px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in operatorTypeLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <span class="filter-label">模块</span>
      <el-select v-model="moduleFilter" style="width: 130px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in moduleLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <span class="filter-label">操作类型</span>
      <el-input
        v-model="actionFilter"
        placeholder="如 CREATE/UPDATE"
        style="width: 140px"
        clearable
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <span class="filter-label">时间范围</span>
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        value-format="YYYY-MM-DD"
        style="width: 260px"
        @change="handleSearch"
      />
    </FilterPanel>

    <el-table v-loading="loading" :data="logs" border @row-click="openDetail">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="operatorName" label="操作人" min-width="110" show-overflow-tooltip />
      <el-table-column label="操作人类型" width="100">
        <template #default="{ row }">
          {{ operatorTypeLabels[row.operatorType] ?? row.operatorType }}
        </template>
      </el-table-column>
      <el-table-column label="模块" width="100">
        <template #default="{ row }">{{ moduleLabel(row.module) }}</template>
      </el-table-column>
      <el-table-column prop="action" label="操作类型" width="110" show-overflow-tooltip />
      <el-table-column prop="description" label="操作描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="操作对象" width="140" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.targetType ? `${row.targetType}#${row.targetId ?? '-'}` : '-' }}
        </template>
      </el-table-column>
      <el-table-column label="IP 地址" width="130">
        <template #default="{ row }">{{ row.ipAddress || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="load"
    />

    <!-- 日志详情 -->
    <el-dialog v-model="detailVisible" title="操作日志详情" width="640px">
      <div v-loading="detailLoading">
        <el-descriptions v-if="detail" :column="2" border>
          <el-descriptions-item label="日志ID">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">{{ formatDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detail.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="操作人类型">
            {{ operatorTypeLabels[detail.operatorType] ?? detail.operatorType }}
          </el-descriptions-item>
          <el-descriptions-item label="操作人ID">{{ detail.operatorId }}</el-descriptions-item>
          <el-descriptions-item label="模块">{{ moduleLabel(detail.module) }}</el-descriptions-item>
          <el-descriptions-item label="操作类型">{{ detail.action }}</el-descriptions-item>
          <el-descriptions-item label="操作对象">
            {{ detail.targetType ? `${detail.targetType}#${detail.targetId ?? '-'}` : '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="IP 地址">{{ detail.ipAddress || '-' }}</el-descriptions-item>
          <el-descriptions-item label="User-Agent" :span="2">
            {{ detail.userAgent || '-' }}
          </el-descriptions-item>
          <el-descriptions-item label="操作描述" :span="2">
            {{ detail.description }}
          </el-descriptions-item>
          <el-descriptions-item label="请求参数" :span="2">
            <pre class="request-params">{{ formatRequestParams(detail.requestParams) }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
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

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

:deep(.el-table__row) {
  cursor: pointer;
}

.request-params {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
