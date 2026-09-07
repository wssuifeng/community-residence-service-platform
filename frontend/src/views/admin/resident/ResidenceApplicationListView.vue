<script setup lang="ts">
/** 入住申请列表：状态筛选 + 通过（生成居住关系与租住记录）/ 驳回（接口设计.md 9.2.2.3 ~ 9.2.2.5） */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  approveResidenceApplication,
  getResidenceApplicationList,
  rejectResidenceApplication
} from '@/api/resident'
import type {
  IResidenceApplication,
  ResidenceApplicationStatus
} from '@/types/modules/resident'
import {
  applicationTypeLabels,
  residenceApplicationStatusLabels
} from '@/types/modules/resident'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

const records = ref<IResidenceApplication[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<ResidenceApplicationStatus | ''>('')

/** 申请状态 → StatusTag 语义色 */
function statusTagType(
  status: ResidenceApplicationStatus
): 'pending' | 'completed' | 'rejected' {
  if (status === 'PENDING') return 'pending'
  if (status === 'APPROVED') return 'completed'
  return 'rejected'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getResidenceApplicationList({
      page: page.value,
      size: size.value,
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

function handleStatusChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  statusFilter.value = ''
  page.value = 1
  load()
}

/* 审批通过：填写租住起止/租金（通过后自动创建居住关系与租住记录） */
const approveVisible = ref(false)
const approveLoading = ref(false)
const approveFormRef = ref<FormInstance>()
const approveTarget = ref<IResidenceApplication | null>(null)
const approveForm = reactive({
  leaseStartDate: '',
  leaseEndDate: '',
  monthlyRent: undefined as number | undefined,
  depositAmount: undefined as number | undefined,
  remark: ''
})

const approveRules: FormRules = {
  leaseStartDate: [{ required: true, message: '请选择租约开始日期', trigger: 'change' }],
  leaseEndDate: [{ required: true, message: '请选择租约结束日期', trigger: 'change' }],
  monthlyRent: [{ required: true, message: '请输入月租金', trigger: 'blur' }]
}

function openApprove(row: IResidenceApplication): void {
  approveTarget.value = row
  approveFormRef.value?.resetFields()
  Object.assign(approveForm, {
    leaseStartDate: row.moveInDate,
    leaseEndDate: '',
    monthlyRent: undefined,
    depositAmount: undefined,
    remark: ''
  })
  approveVisible.value = true
}

async function handleApprove(): Promise<void> {
  if (!approveTarget.value) return
  const valid = await approveFormRef.value?.validate().catch(() => false)
  if (!valid) return
  approveLoading.value = true
  try {
    await approveResidenceApplication(approveTarget.value.id, {
      leaseStartDate: approveForm.leaseStartDate,
      leaseEndDate: approveForm.leaseEndDate,
      monthlyRent: approveForm.monthlyRent as number,
      depositAmount: approveForm.depositAmount,
      remark: approveForm.remark.trim() || undefined
    })
    ElMessage.success('审批通过，已创建居住关系与租住记录')
    approveVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审批通过失败')
  } finally {
    approveLoading.value = false
  }
}

/* 审批驳回：必须填写理由 */
async function handleReject(row: IResidenceApplication): Promise<void> {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `驳回「${row.residentName}」申请入住「${row.houseAddress}」的申请，理由将通知申请人。`,
      '驳回入住申请',
      {
        type: 'warning',
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '驳回理由（必填）',
        inputValidator: (value: string) =>
          value.trim().length > 0 ? true : '驳回理由不能为空'
      }
    )
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await rejectResidenceApplication(row.id, { reason })
    ElMessage.success('已驳回该申请')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '驳回失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="application-list">
    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">状态</span>
      <el-select v-model="statusFilter" style="width: 120px" @change="handleStatusChange">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in residenceApplicationStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="records" border>
      <el-table-column prop="residentName" label="申请人" min-width="100" show-overflow-tooltip />
      <el-table-column label="申请类型" width="100">
        <template #default="{ row }">
          {{ applicationTypeLabels[row.applicationType as keyof typeof applicationTypeLabels] }}
        </template>
      </el-table-column>
      <el-table-column prop="houseAddress" label="目标房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="申请时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <StatusTag
            :label="residenceApplicationStatusLabels[row.status as ResidenceApplicationStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="primary" size="small" @click="openApprove(row)">通过</el-button>
            <el-button link type="danger" size="small" @click="handleReject(row)">驳回</el-button>
          </template>
          <span v-else class="op-done">-</span>
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

    <!-- 审批通过：填写租住信息 -->
    <el-dialog v-model="approveVisible" title="审批通过" width="520px">
      <el-alert
        v-if="approveTarget"
        :title="`通过后将为「${approveTarget.residentName}」自动创建「${approveTarget.houseAddress}」的居住关系与租住记录`"
        type="info"
        :closable="false"
        class="approve-tip"
      />
      <el-form ref="approveFormRef" :model="approveForm" :rules="approveRules" label-width="100px">
        <el-form-item label="租约开始" prop="leaseStartDate">
          <el-date-picker
            v-model="approveForm.leaseStartDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择开始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="租约结束" prop="leaseEndDate">
          <el-date-picker
            v-model="approveForm.leaseEndDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="月租金（元）" prop="monthlyRent">
          <el-input-number
            v-model="approveForm.monthlyRent"
            :min="0"
            :precision="2"
            :step="100"
            placeholder="请输入月租金"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金（元）" prop="depositAmount">
          <el-input-number
            v-model="approveForm.depositAmount"
            :min="0"
            :precision="2"
            :step="100"
            placeholder="选填"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="approveForm.remark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="approveLoading" @click="handleApprove">
          确认通过
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.op-done {
  color: var(--color-text-disabled);
}

.approve-tip {
  margin-bottom: var(--spacing-md);
}
</style>
