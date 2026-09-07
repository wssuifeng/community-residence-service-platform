<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import {
  listReservations,
  confirmReservation,
  rejectReservation,
  completeReservation,
  violateReservation
} from '@/api/reservation'
import type { IResourceReservation, ReservationStatus } from '@/types/modules/reservation'
import { reservationStatusLabels } from '@/types/modules/reservation'
import { formatDateTime } from '@/utils/date'

/**
 * 预约列表（管理端）：筛选 + 审核（PENDING 通过/拒绝带理由）
 * + CONFIRMED 行完成/违约处置（状态机：待审核→已预约→已完成 + 拒绝/取消/违约）
 */

const statusOptions = (Object.keys(reservationStatusLabels) as ReservationStatus[]).map((value) => ({
  value,
  label: reservationStatusLabels[value]
}))

const tagTypeMap: Record<ReservationStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  CONFIRMED: 'processing',
  COMPLETED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const query = reactive({
  page: 1,
  size: 10,
  status: undefined as ReservationStatus | undefined,
  dateRange: null as [string, string] | null
})

const total = ref(0)
const records = ref<IResourceReservation[]>([])
const loading = ref(false)

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listReservations({
      page: query.page,
      size: query.size,
      status: query.status,
      startDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1]
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载预约列表失败')
  } finally {
    loading.value = false
  }
}

function handleReset(): void {
  query.status = undefined
  query.dateRange = null
  query.page = 1
  loadList()
}

/* ------------------------------ 审核与处置 ------------------------------ */

/** 审核通过（PENDING → CONFIRMED），备注选填 */
async function handleConfirm(row: IResourceReservation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `${row.resourceName} ${row.reservationDate} ${row.startTime} ~ ${row.endTime}，使用人数 ${row.participants}`,
      `审核通过 ${row.reservationNumber}`,
      {
        confirmButtonText: '通过',
        cancelButtonText: '取消',
        inputPlaceholder: '审核备注（选填）',
        type: 'success',
        inputValue: ''
      }
    )
    await confirmReservation(row.id, value.trim() ? { remark: value.trim() } : undefined)
    ElMessage.success('已通过审核')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '审核操作失败')
  }
}

/** 审核拒绝（PENDING → REJECTED），理由必填 */
async function handleReject(row: IResourceReservation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由', `拒绝预约 ${row.reservationNumber}`, {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：该时段资源维护，暂停开放',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写拒绝理由'),
      type: 'warning'
    })
    await rejectReservation(row.id, { reason: value.trim() })
    ElMessage.success('已拒绝该预约')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '拒绝操作失败')
  }
}

/** 完成预约（CONFIRMED → COMPLETED） */
async function handleComplete(row: IResourceReservation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认「${row.resourceName}」${row.reservationDate} 的预约已完成使用？`,
      `完成预约 ${row.reservationNumber}`,
      { confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'success' }
    )
    await completeReservation(row.id)
    ElMessage.success('已标记完成')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '完成操作失败')
  }
}

/** 标记违约（CONFIRMED → VIOLATED），理由必填，违约将计入居民违约记录 */
async function handleViolate(row: IResourceReservation): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      '违约将计入该居民的违约记录，请谨慎操作',
      `标记违约 ${row.reservationNumber}`,
      {
        confirmButtonText: '确认标记违约',
        cancelButtonText: '取消',
        inputPlaceholder: '违约原因（如：预约后未到场）',
        inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写违约原因'),
        type: 'error'
      }
    )
    await violateReservation(row.id, { reason: value.trim() })
    ElMessage.success('已标记违约')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '违约标记失败')
  }
}

onMounted(loadList)
</script>

<template>
  <section class="reservation-admin">
    <header class="page-head">
      <h1>预约列表</h1>
      <p class="page-head-sub">审核居民提交的资源预约，并对已预约的时段进行完成/违约处置</p>
    </header>

    <FilterPanel resettable @reset="handleReset">
      <el-select
        v-model="query.status"
        placeholder="预约状态"
        clearable
        style="width: 160px"
        @change="query.page = 1; loadList()"
      >
        <el-option v-for="option in statusOptions" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="预约开始日期"
        end-placeholder="预约结束日期"
        value-format="YYYY-MM-DD"
        style="width: 280px"
        @change="query.page = 1; loadList()"
      />
    </FilterPanel>

    <el-table v-loading="loading" :data="records" stripe>
      <el-table-column prop="reservationNumber" label="预约单号" min-width="150" />
      <el-table-column prop="residentName" label="预约居民" min-width="90" />
      <el-table-column prop="resourceName" label="公共资源" min-width="120" />
      <el-table-column label="预约时间" min-width="180">
        <template #default="{ row }">
          {{ row.reservationDate }} {{ row.startTime }} ~ {{ row.endTime }}
        </template>
      </el-table-column>
      <el-table-column prop="participants" label="人数" width="60" align="center" />
      <el-table-column prop="purpose" label="用途" min-width="140" show-overflow-tooltip />
      <el-table-column prop="contactPhone" label="联系电话" min-width="120" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <StatusTag :label="reservationStatusLabels[row.status as ReservationStatus]" :type="tagTypeMap[row.status as ReservationStatus]" />
        </template>
      </el-table-column>
      <el-table-column label="提交时间" min-width="140">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="primary" size="small" @click="handleConfirm(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="handleReject(row)">拒绝</el-button>
          </template>
          <template v-else-if="row.status === 'CONFIRMED'">
            <el-button link type="success" size="small" @click="handleComplete(row)">完成</el-button>
            <el-button link type="danger" size="small" @click="handleViolate(row)">违约</el-button>
          </template>
          <span v-else class="no-action">—</span>
        </template>
      </el-table-column>
    </el-table>

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

.no-action {
  color: var(--color-text-disabled);
}
</style>
