<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import { listReservations, cancelReservation } from '@/api/reservation'
import type { IResourceReservation, ReservationStatus } from '@/types/modules/reservation'
import { reservationStatusLabels } from '@/types/modules/reservation'
import { formatDateTime } from '@/utils/date'

/** 我的预约：状态筛选卡片 + 列表 + 取消（PENDING/CONFIRMED 可取消，状态机 9.7.1.7） */

const statusFilters = [
  { value: '', label: '全部' },
  ...(Object.keys(reservationStatusLabels) as ReservationStatus[]).map((value) => ({
    value,
    label: reservationStatusLabels[value]
  }))
]

const query = reactive({
  page: 1,
  size: 10,
  status: '' as '' | ReservationStatus
})
const total = ref(0)
const records = ref<IResourceReservation[]>([])
const loading = ref(false)

/** 状态 → StatusTag 语义色 */
const tagTypeMap: Record<ReservationStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  CONFIRMED: 'processing',
  COMPLETED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const canCancel = (row: IResourceReservation): boolean =>
  row.status === 'PENDING' || row.status === 'CONFIRMED'

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listReservations({
      page: query.page,
      size: query.size,
      status: query.status === '' ? undefined : query.status
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载预约列表失败')
  } finally {
    loading.value = false
  }
}

function handleStatusChange(status: '' | ReservationStatus): void {
  query.status = status
  query.page = 1
  loadList()
}

/** 取消需填写原因（ReservationReasonDTO.reason 必填） */
async function handleCancel(row: IResourceReservation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', `取消预约 ${row.reservationNumber}`, {
      confirmButtonText: '确认取消预约',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：临时有事，无法按时使用',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写取消原因'),
      type: 'warning'
    })
    await cancelReservation(row.id, { reason: value.trim() })
    ElMessage.success('预约已取消')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消预约失败')
  }
}

const isEmpty = computed(() => !loading.value && records.value.length === 0)

onMounted(loadList)
</script>

<template>
  <section class="reservation-list">
    <header class="page-head">
      <div>
        <h1>我的预约</h1>
        <p class="page-head-sub">社区公共资源预约记录，待审核与已预约的预约可取消</p>
      </div>
      <router-link to="/resident/reservations/create">
        <el-button type="primary" round>＋ 发起预约</el-button>
      </router-link>
    </header>

    <div class="status-filter" role="tablist">
      <button
        v-for="filter in statusFilters"
        :key="filter.value"
        class="status-chip"
        :class="{ 'is-active': query.status === filter.value }"
        @click="handleStatusChange(filter.value as '' | ReservationStatus)"
      >
        {{ filter.label }}
      </button>
    </div>

    <div v-loading="loading" class="list-body">
      <div v-if="isEmpty" class="empty-state">
        <img src="/images/empty-state.png" alt="暂无预约" />
        <p>还没有预约记录，去发起一个吧</p>
        <router-link to="/resident/reservations/create">
          <el-button type="primary" plain round>发起预约</el-button>
        </router-link>
      </div>

      <article v-for="row in records" v-else :key="row.id" class="reservation-card">
        <div class="card-main">
          <div class="card-title-row">
            <h2 class="card-title">{{ row.resourceName }}</h2>
            <StatusTag :label="reservationStatusLabels[row.status]" :type="tagTypeMap[row.status]" />
          </div>
          <dl class="card-meta">
            <div class="meta-item">
              <dt>预约时间</dt>
              <dd>{{ row.reservationDate }} {{ row.startTime }} ~ {{ row.endTime }}</dd>
            </div>
            <div class="meta-item">
              <dt>使用人数</dt>
              <dd>{{ row.participants }} 人</dd>
            </div>
            <div class="meta-item">
              <dt>预约单号</dt>
              <dd class="mono">{{ row.reservationNumber }}</dd>
            </div>
            <div class="meta-item">
              <dt>提交时间</dt>
              <dd>{{ formatDateTime(row.createdAt) }}</dd>
            </div>
          </dl>
          <p v-if="row.purpose" class="card-purpose">用途：{{ row.purpose }}</p>
          <p v-if="row.remark" class="card-purpose is-remark">备注：{{ row.remark }}</p>
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
.reservation-list {
  display: flex;
  flex-direction: column;
}

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

.reservation-card {
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

.reservation-card:hover {
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

.card-purpose {
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.card-purpose.is-remark {
  color: var(--color-text-disabled);
}

.card-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
</style>
