<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { listViewingAppointments, cancelViewingAppointment } from '@/api/housing'
import type { IViewingAppointment, ViewingAppointmentStatus } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import { formatDateTime } from '@/utils/date'

/** 我的看房预约（居民端）：状态筛选 + 列表 + 取消（PENDING/CONFIRMED 可取消） */

const statusFilters = [
  { value: '', label: '全部' },
  ...(Object.keys(viewingAppointmentStatusLabels) as ViewingAppointmentStatus[]).map((value) => ({
    value,
    label: viewingAppointmentStatusLabels[value]
  }))
]

const tagTypeMap: Record<ViewingAppointmentStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  CONFIRMED: 'processing',
  COMPLETED: 'completed',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const query = reactive({
  page: 1,
  size: 10,
  status: '' as '' | ViewingAppointmentStatus
})

const total = ref(0)
const records = ref<IViewingAppointment[]>([])
const loading = ref(false)

const canCancel = (row: IViewingAppointment): boolean =>
  row.status === 'PENDING' || row.status === 'CONFIRMED'

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listViewingAppointments({
      page: query.page,
      size: query.size,
      status: query.status === '' ? undefined : query.status
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看房预约失败')
  } finally {
    loading.value = false
  }
}

function handleStatusChange(status: '' | ViewingAppointmentStatus): void {
  query.status = status
  query.page = 1
  loadList()
}

/** 取消需填写原因（ViewingAppointmentReasonDTO.reason 必填） */
async function handleCancel(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', `取消看房 ${row.appointmentNumber}`, {
      confirmButtonText: '确认取消',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：时间冲突，改期再看',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写取消原因'),
      type: 'warning'
    })
    await cancelViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('看房预约已取消')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消看房预约失败')
  }
}

const isEmpty = computed(() => !loading.value && records.value.length === 0)

onMounted(loadList)
</script>

<template>
  <section class="viewing-list">
    <header class="page-head">
      <div>
        <h1>我的看房预约</h1>
        <p class="page-head-sub">预约记录与状态，待确认与已预约的看房可取消</p>
      </div>
      <router-link to="/resident/housings">
        <el-button type="primary" round>去挑房源</el-button>
      </router-link>
    </header>

    <div class="status-filter" role="tablist">
      <button
        v-for="filter in statusFilters"
        :key="filter.value"
        class="status-chip"
        :class="{ 'is-active': query.status === filter.value }"
        @click="handleStatusChange(filter.value as '' | ViewingAppointmentStatus)"
      >
        {{ filter.label }}
      </button>
    </div>

    <div v-loading="loading" class="list-body">
      <div v-if="isEmpty" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无看房预约" />
        <p>还没有看房预约，挑一个心仪的房源预约看看吧</p>
        <router-link to="/resident/housings">
          <el-button type="primary" plain round>浏览房源</el-button>
        </router-link>
      </div>

      <article v-for="row in records" v-else :key="row.id" class="viewing-card">
        <div class="card-main">
          <div class="card-title-row">
            <h2 class="card-title">{{ row.housingTitle }}</h2>
            <StatusTag :label="viewingAppointmentStatusLabels[row.status]" :type="tagTypeMap[row.status]" />
          </div>
          <dl class="card-meta">
            <div class="meta-item">
              <dt>看房时间</dt>
              <dd>{{ row.appointmentDate }} {{ row.startTime }} ~ {{ row.endTime }}</dd>
            </div>
            <div class="meta-item">
              <dt>看房人</dt>
              <dd>{{ row.visitorName }}（{{ row.visitorCount }} 人）</dd>
            </div>
            <div class="meta-item">
              <dt>预约单号</dt>
              <dd class="mono">{{ row.appointmentNumber }}</dd>
            </div>
            <div class="meta-item">
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(row.createdAt) }}</dd>
            </div>
          </dl>
          <p v-if="row.remark" class="card-remark">备注：{{ row.remark }}</p>
        </div>
        <div class="card-actions">
          <el-button
            v-if="canCancel(row)"
            type="danger"
            plain
            round
            size="small"
            @click="handleCancel(row)"
          >
            取消预约
          </el-button>
        </div>
      </article>
    </div>

    <Pagination
      v-model:page="query.page"
      v-model:size="query.size"
      :total="total"
      @update:page="loadList"
      @update:size="loadList"
    />
  </section>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--spacing-lg);
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

.status-filter {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.status-chip {
  padding: 6px var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background-color: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: all 0.2s ease;
}

.status-chip:hover {
  border-color: var(--color-primary-light);
  color: var(--color-primary);
}

.status-chip.is-active {
  background-color: var(--color-primary);
  border-color: var(--color-primary);
  color: #fff;
  font-weight: var(--font-weight-medium);
}

.list-body {
  min-height: 240px;
}

.empty-state {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xxl) var(--spacing-lg);
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state img {
  width: 120px;
  margin: 0 auto var(--spacing-md);
}

.empty-state p {
  margin-bottom: var(--spacing-md);
}

.viewing-card {
  display: flex;
  justify-content: space-between;
  gap: var(--spacing-md);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  margin-bottom: var(--spacing-md);
  box-shadow: var(--shadow-sm);
  transition: box-shadow 0.2s ease;
}

.viewing-card:hover {
  box-shadow: var(--shadow-md);
}

.card-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
}

.card-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.card-meta {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--spacing-sm) var(--spacing-md);
}

.meta-item dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.meta-item dd {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.mono {
  font-family: var(--font-family-mono);
}

.card-remark {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.card-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
</style>
