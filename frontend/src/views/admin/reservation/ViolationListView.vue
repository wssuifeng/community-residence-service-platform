<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import { listResidentViolations } from '@/api/reservation'
import type { IViolationRecord, ViolationType } from '@/types/modules/reservation'
import { violationTypeLabels } from '@/types/modules/reservation'
import { formatDateTime } from '@/utils/date'

/**
 * 违约记录（管理端）：按居民查询违约记录（接口 9.7.2.1 按居民维度查询，
 * 违约由预约列表的「违约」处置产生，本页为只读档案视图）
 */

const typeOptions = (Object.keys(violationTypeLabels) as ViolationType[]).map((value) => ({
  value,
  label: violationTypeLabels[value]
}))

const query = reactive({
  page: 1,
  size: 10,
  type: undefined as ViolationType | undefined
})

/** 违约查询以居民为单位（接口契约），输入居民 ID 后查询 */
const residentIdInput = ref<number | null>(null)
const searchedResidentId = ref<number | null>(null)

const total = ref(0)
const records = ref<IViolationRecord[]>([])
const loading = ref(false)

async function loadList(): Promise<void> {
  if (searchedResidentId.value === null) return
  loading.value = true
  try {
    const result = await listResidentViolations(searchedResidentId.value, {
      page: query.page,
      size: query.size,
      type: query.type
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载违约记录失败')
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  if (residentIdInput.value === null) {
    ElMessage.warning('请先输入要查询的居民 ID')
    return
  }
  searchedResidentId.value = residentIdInput.value
  query.page = 1
  loadList()
}

function handleReset(): void {
  query.type = undefined
  query.page = 1
  loadList()
}

const isEmpty = computed(() => !loading.value && searchedResidentId.value !== null && records.value.length === 0)

onMounted(() => {
  /* 进入页面不自动加载：接口需指定居民 ID */
})
</script>

<template>
  <section class="violation-admin">
    <header class="page-head">
      <h1>违约记录</h1>
      <p class="page-head-sub">
        居民资源预约/看房预约的违约档案；违约在预约列表的「违约」处置中登记，此处按居民查询
      </p>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <el-input-number
        v-model="residentIdInput"
        :min="1"
        :controls="false"
        placeholder="输入居民 ID"
        style="width: 160px"
        @keyup.enter="handleSearch"
      />
      <el-button type="primary" @click="handleSearch">查询</el-button>
      <el-divider direction="vertical" />
      <el-select
        v-model="query.type"
        placeholder="违约类型"
        clearable
        :disabled="searchedResidentId === null"
        style="width: 160px"
        @change="query.page = 1; loadList()"
      >
        <el-option v-for="option in typeOptions" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
    </FilterPanel>

    <template v-if="searchedResidentId === null">
      <div class="empty-state">
        <img src="/images/empty-state.png" alt="请输入居民 ID" />
        <p>输入居民 ID 后查询其违约记录</p>
      </div>
    </template>
    <template v-else>
      <el-table v-loading="loading" :data="records" stripe>
        <el-table-column prop="residentName" label="居民" min-width="90" />
        <el-table-column label="违约类型" width="110" align="center">
          <template #default="{ row }">
            <StatusTag
              :label="violationTypeLabels[row.violationType as ViolationType]"
              :type="row.violationType === 'RESOURCE_RESERVATION' ? 'processing' : 'info'"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sourceNumber" label="关联单号" min-width="160" />
        <el-table-column prop="reason" label="违约原因" min-width="200" show-overflow-tooltip />
        <el-table-column prop="handlerName" label="处理人" min-width="90" />
        <el-table-column label="登记时间" min-width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div v-if="isEmpty" class="table-empty-hint">该居民暂无违约记录</div>

      <Pagination
        v-model:page="query.page"
        v-model:size="query.size"
        :total="total"
        @update:page="loadList"
        @update:size="loadList"
      />
    </template>
  </section>
</template>

<style scoped>
.page-head {
  margin-bottom: var(--spacing-md);
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.empty-state {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-xxl) var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state img {
  width: 120px;
  margin: 0 auto var(--spacing-md);
}

.table-empty-hint {
  padding: var(--spacing-lg);
  text-align: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}
</style>
