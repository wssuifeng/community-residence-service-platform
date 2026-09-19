<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import {
  listViewingAppointments,
  confirmViewingAppointment,
  cancelViewingAppointment,
  completeViewingAppointment,
  violateViewingAppointment,
  assignViewingAppointment,
  listAssignableAssignees
} from '@/api/housing'
import type { IViewingAppointment, ViewingAppointmentStatus } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import type { SystemRole } from '@/types/modules/auth'
import { systemRoleLabels } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'

/**
 * 看房预约管理（管理端 Tab 内容）：筛选 + TO_CONFIRM 确认/拒绝 + RESERVED 完成/违约处置
 * + 带看人分配（R59：目标为启用状态服务人员或社区管理员，分配后后端通知双方）。
 * （看房预约无独立拒绝接口，拒绝走 cancel 携带理由，状态机 9.12.2）
 * 列表展示字段对齐后端 ViewingAppointmentVO 真实返回：联系电话为 contactPhone，
 * appointmentNumber/visitorCount 后端不返回不再展示（漂移收口，2026-09-12）；
 * 处置动作后 emit changed 供容器刷新统计卡与角标。
 */

const emit = defineEmits<{
  changed: []
}>()

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

/** 确认（TO_CONFIRM → RESERVED）；后端 ReservationActionDTO 要求 reason 必填，确认备注作 reason 传递 */
async function handleConfirm(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `${row.housingTitle} · ${row.appointmentDate} ${row.startTime} ~ ${row.endTime}，看房人 ${row.visitorName}`,
      '确认看房预约',
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputPlaceholder: '确认备注（必填）',
        inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写确认备注'),
        type: 'success'
      }
    )
    await confirmViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('已确认该看房预约')
    loadList()
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '确认操作失败')
  }
}

/** 拒绝（走 cancel 接口携带理由，TO_CONFIRM → CANCELLED） */
async function handleReject(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由', `拒绝看房 · ${row.housingTitle}`, {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：该时段房东不便接待',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写拒绝理由'),
      type: 'warning'
    })
    await cancelViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('已拒绝该看房预约')
    loadList()
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '拒绝操作失败')
  }
}

/** 完成（RESERVED → COMPLETED）；后端 ReservationActionDTO 要求 reason 必填 */
async function handleComplete(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认「${row.housingTitle}」${row.appointmentDate} 的看房已完成？`,
      '完成看房',
      {
        confirmButtonText: '确认完成',
        cancelButtonText: '取消',
        inputPlaceholder: '完成备注（必填）',
        inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写完成备注'),
        type: 'success'
      }
    )
    await completeViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('已标记完成')
    loadList()
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '完成操作失败')
  }
}

/** 违约（RESERVED → VIOLATED），理由必填，计入居民违约记录 */
async function handleViolate(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      '违约将计入访客的违约记录，请谨慎操作',
      `标记违约 · ${row.housingTitle}`,
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
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '违约标记失败')
  }
}

/* ------------------------------ 带看人分配（R59） ------------------------------ */

/* 候选人下拉选项：最小暴露面仅 id/姓名/角色 */
interface AssigneeOption {
  id: number
  realName: string
  role: SystemRole
}

const assignDialogVisible = ref(false)
const candidatesLoading = ref(false)
const candidates = ref<AssigneeOption[]>([])
const assigneeId = ref<number | null>(null)
const assigning = ref(false)
const assigningRow = ref<IViewingAppointment | null>(null)

const assigningRowLabel = computed(() => {
  const row = assigningRow.value
  if (!row) return ''
  return `${row.housingTitle} · ${row.appointmentDate} ${row.startTime} ~ ${row.endTime}`
})

async function loadCandidates(): Promise<void> {
  const row = assigningRow.value
  if (!row) return
  candidatesLoading.value = true
  try {
    /* 候选=STAFF 全量 + 管辖该房源社区的启用 ADMIN（后端按 communityId 过滤并校验调用者绑定） */
    candidates.value = await listAssignableAssignees(row.communityId)
  } catch (error) {
    candidates.value = []
    ElMessage.error(error instanceof Error ? error.message : '带看人候选列表加载失败')
  } finally {
    candidatesLoading.value = false
  }
}

/** 打开分配弹窗：已有带看人时预选当前人（可更换） */
function openAssignDialog(row: IViewingAppointment): void {
  assigningRow.value = row
  assigneeId.value = row.assigneeId ?? null
  assignDialogVisible.value = true
  void loadCandidates()
}

async function handleAssignConfirm(): Promise<void> {
  const row = assigningRow.value
  if (!row || !assigneeId.value) {
    ElMessage.warning('请选择带看人')
    return
  }
  assigning.value = true
  try {
    await assignViewingAppointment(row.id, { assigneeId: assigneeId.value })
    ElMessage.success('已分配带看人，将通知带看人与预约居民')
    assignDialogVisible.value = false
    loadList()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '分配带看人失败')
  } finally {
    assigning.value = false
  }
}

onMounted(loadList)
</script>

<template>
  <section class="viewing-list-admin">
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
      <el-table-column prop="housingTitle" label="房源" min-width="170" show-overflow-tooltip />
      <el-table-column label="看房时间" min-width="180">
        <template #default="{ row }">
          {{ row.appointmentDate }} {{ row.startTime }} ~ {{ row.endTime }}
        </template>
      </el-table-column>
      <el-table-column prop="visitorName" label="看房人" min-width="90" />
      <el-table-column prop="contactPhone" label="联系电话" min-width="120" />
      <el-table-column label="带看人" min-width="90">
        <template #default="{ row }">
          <span v-if="row.assigneeName">{{ row.assigneeName }}</span>
          <span v-else class="no-assignee">未分配</span>
        </template>
      </el-table-column>
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
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'TO_CONFIRM'">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" size="small" @click="handleConfirm(row)">确认</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleReject(row)">拒绝</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="warning" size="small" @click="openAssignDialog(row)">分配带看人</el-button>
          </template>
          <template v-else-if="row.status === 'RESERVED'">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="success" size="small" @click="handleComplete(row)">完成</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="danger" size="small" @click="handleViolate(row)">违约</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="warning" size="small" @click="openAssignDialog(row)">分配带看人</el-button>
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

    <!-- 分配带看人弹窗（R59）：候选=启用状态服务人员与社区管理员，最小暴露面仅 id/姓名/角色 -->
    <el-dialog v-model="assignDialogVisible" title="分配带看人" width="480px">
      <p class="assign-context">{{ assigningRowLabel }}</p>
      <p v-if="assigningRow?.assigneeName" class="assign-current">
        当前带看人：{{ assigningRow.assigneeName }}（可更换）
      </p>
      <el-select
        v-model="assigneeId"
        :loading="candidatesLoading"
        placeholder="选择带看人（启用状态的服务人员/社区管理员）"
        filterable
        style="width: 100%"
      >
        <el-option v-for="option in candidates" :key="option.id" :value="option.id" :label="option.realName">
          <span class="assignee-option">
            <span>{{ option.realName }}</span>
            <span class="assignee-role">{{ systemRoleLabels[option.role] }}</span>
          </span>
        </el-option>
      </el-select>
      <template #footer>
        <el-button @click="assignDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="assigning" :disabled="!assigneeId" @click="handleAssignConfirm">
          确认分配
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.no-action {
  color: var(--color-text-disabled);
}

.no-assignee {
  color: var(--color-text-disabled);
}

.assign-context {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.assign-current {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-warning);
}

.assignee-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
}

.assignee-role {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
