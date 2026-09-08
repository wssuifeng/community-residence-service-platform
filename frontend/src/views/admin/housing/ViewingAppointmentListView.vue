<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import {
  listViewingAppointments,
  confirmViewingAppointment,
  cancelViewingAppointment,
  completeViewingAppointment,
  violateViewingAppointment
} from '@/api/housing'
import type { IViewingAppointment, ViewingAppointmentStatus } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import { formatDateTime } from '@/utils/date'

/**
 * 看房预约管理（管理端）：筛选 + PENDING 确认/拒绝 + CONFIRMED 完成/违约处置
 * （看房预约无独立拒绝接口，拒绝走 cancel 携带理由，状态机 9.12.2）
 */

const statusOptions = (Object.keys(viewingAppointmentStatusLabels) as ViewingAppointmentStatus[]).map(
  (value) => ({ value, label: viewingAppointmentStatusLabels[value] })
)

const tagTypeMap: Record<ViewingAppointmentStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  TO_CONFIRM: 'pending',
  RESERVED: 'processing',
  COMPLETED: 'completed',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const query = reactive({
  page: 1,
  size: 10,
  status: undefined as ViewingAppointmentStatus | undefined,
  dateRange: null as [string, string] | null
})

const total = ref(0)
const records = ref<IViewingAppointment[]>([])
const loading = ref(false)

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listViewingAppointments({
      page: query.page,
      size: query.size,
      status: query.status,
      startDate: query.dateRange?.[0],
      endDate: query.dateRange?.[1]
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看房预约失败')
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

/* ------------------------------ 确认与处置 ------------------------------ */

/** 确认（PENDING → CONFIRMED），备注选填 */
async function handleConfirm(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `${row.housingTitle} · ${row.appointmentDate} ${row.startTime} ~ ${row.endTime}，看房人 ${row.visitorName}（${row.visitorCount} 人）`,
      `确认预约 ${row.appointmentNumber}`,
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputPlaceholder: '备注（选填）',
        type: 'success',
        inputValue: ''
      }
    )
    await confirmViewingAppointment(row.id, value.trim() ? { remark: value.trim() } : undefined)
    ElMessage.success('已确认该看房预约')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '确认操作失败')
  }
}

/** 拒绝（走 cancel 接口携带理由，PENDING → CANCELLED） */
async function handleReject(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由', `拒绝看房 ${row.appointmentNumber}`, {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：该时段房东不便接待',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写拒绝理由'),
      type: 'warning'
    })
    await cancelViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('已拒绝该看房预约')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '拒绝操作失败')
  }
}

/** 完成（CONFIRMED → COMPLETED） */
async function handleComplete(row: IViewingAppointment): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认「${row.housingTitle}」${row.appointmentDate} 的看房已完成？`,
      `完成看房 ${row.appointmentNumber}`,
      { confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'success' }
    )
    await completeViewingAppointment(row.id)
    ElMessage.success('已标记完成')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '完成操作失败')
  }
}

/** 违约（CONFIRMED → VIOLATED），理由必填，计入居民违约记录 */
async function handleViolate(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      '违约将计入访客的违约记录，请谨慎操作',
      `标记违约 ${row.appointmentNumber}`,
      {
        confirmButtonText: '确认标记违约',
        cancelButtonText: '取消',
        inputPlaceholder: '违约原因（如：预约后未到场）',
        inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写违约原因'),
        type: 'error'
      }
    )
    await violateViewingAppointment(row.id, { reason: value.trim() })
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
  <section class="viewing-admin">
    <header class="page-head">
      <h1>看房预约管理</h1>
      <p class="page-head-sub">确认居民提交的看房申请，并对已预约的看房进行完成/违约处置</p>
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
        start-placeholder="看房开始日期"
        end-placeholder="看房结束日期"
        value-format="YYYY-MM-DD"
        style="width: 280px"
        @change="query.page = 1; loadList()"
      />
    </FilterPanel>

    <el-table v-loading="loading" :data="records" stripe>
      <el-table-column prop="appointmentNumber" label="预约单号" min-width="150" />
      <el-table-column prop="housingTitle" label="房源" min-width="150" show-overflow-tooltip />
      <el-table-column label="看房时间" min-width="180">
        <template #default="{ row }">
          {{ row.appointmentDate }} {{ row.startTime }} ~ {{ row.endTime }}
        </template>
      </el-table-column>
      <el-table-column prop="visitorName" label="看房人" min-width="90" />
      <el-table-column prop="visitorPhone" label="联系电话" min-width="120" />
      <el-table-column prop="visitorCount" label="人数" width="60" align="center" />
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <StatusTag
            :label="viewingAppointmentStatusLabels[row.status as ViewingAppointmentStatus]"
            :type="tagTypeMap[row.status as ViewingAppointmentStatus]"
          />
        </template>
      </el-table-column>
      <el-table-column label="提交时间" min-width="140">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'TO_CONFIRM'">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="handleConfirm(row)">确认</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleReject(row)">拒绝</el-button>
          </template>
          <template v-else-if="row.status === 'RESERVED'">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="success" size="small" @click="handleComplete(row)">完成</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleViolate(row)">违约</el-button>
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
