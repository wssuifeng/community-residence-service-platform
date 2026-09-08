<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createLease,
  getLeaseList,
  updateLease,
  updateLeaseStatus
} from '@/api/lease'
import type { ILeaseRecord, LeaseStatus, PaymentMethod } from '@/types/modules/lease'
import { leaseStatusLabels, paymentMethodLabels } from '@/types/modules/lease'
import { formatDate } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 租住记录列表：状态筛选 + 新建/编辑 + 终止操作 */
const leases = ref<ILeaseRecord[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<LeaseStatus | ''>('')

/** 租住状态 → StatusTag 语义色（生效 completed / 到期、终止 canceled） */
function statusTagType(status: LeaseStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getLeaseList({
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined
    })
    leases.value = result.records
    total.value = result.total
  } catch {
    leases.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleFilterChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  statusFilter.value = ''
  page.value = 1
  load()
}

/* ---------------- 新建/编辑对话框 ---------------- */

interface LeaseForm {
  residentId: number | undefined
  houseId: number | undefined
  leaseStartDate: string
  leaseEndDate: string
  monthlyRent: number | undefined
  depositAmount: number | undefined
  paymentMethod: PaymentMethod
  contractNumber: string
  remark: string
}

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<LeaseForm>({
  residentId: undefined,
  houseId: undefined,
  leaseStartDate: '',
  leaseEndDate: '',
  monthlyRent: undefined,
  depositAmount: undefined,
  paymentMethod: 'MONTHLY',
  contractNumber: '',
  remark: ''
})

const formRules: FormRules = {
  residentId: [{ required: true, message: '请输入居民 ID', trigger: 'blur' }],
  houseId: [{ required: true, message: '请输入房屋 ID', trigger: 'blur' }],
  leaseStartDate: [{ required: true, message: '请选择租期开始日期', trigger: 'change' }],
  leaseEndDate: [{ required: true, message: '请选择租期结束日期', trigger: 'change' }],
  monthlyRent: [{ required: true, message: '请输入月租金', trigger: 'blur' }]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    residentId: undefined,
    houseId: undefined,
    leaseStartDate: '',
    leaseEndDate: '',
    monthlyRent: undefined,
    depositAmount: undefined,
    paymentMethod: 'MONTHLY',
    contractNumber: '',
    remark: ''
  })
  dialogVisible.value = true
}

function openEdit(row: ILeaseRecord): void {
  editingId.value = row.id
  Object.assign(form, {
    residentId: row.residentId,
    houseId: row.houseId,
    leaseStartDate: formatDate(row.leaseStartDate),
    leaseEndDate: formatDate(row.leaseEndDate),
    monthlyRent: row.monthlyRent,
    depositAmount: row.depositAmount,
    paymentMethod: row.paymentMethod ?? 'MONTHLY',
    contractNumber: row.contractNumber ?? '',
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (form.leaseEndDate <= form.leaseStartDate) {
    ElMessage.error('租期结束日期必须晚于开始日期')
    return
  }
  const payload = {
    residentId: form.residentId!,
    houseId: form.houseId!,
    leaseStartDate: form.leaseStartDate,
    leaseEndDate: form.leaseEndDate,
    monthlyRent: form.monthlyRent!,
    depositAmount: form.depositAmount ?? undefined,
    paymentMethod: form.paymentMethod,
    contractNumber: form.contractNumber.trim() || undefined,
    remark: form.remark.trim() || undefined
  }
  try {
    if (editingId.value === null) {
      await createLease(payload)
      ElMessage.success('租住记录已创建')
    } else {
      await updateLease(editingId.value, payload)
      ElMessage.success('租住记录已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------- 状态变更（终止） ---------------- */

/** 终止租住：非已终止记录可操作，备注可选 */
async function handleTerminate(row: ILeaseRecord): Promise<void> {
  let remark: string
  try {
    const result = await ElMessageBox.prompt(
      `确定终止「${row.residentName} · ${row.houseAddress}」的租住记录？可填写终止原因。`,
      '终止租住',
      {
        type: 'warning',
        confirmButtonText: '终止',
        cancelButtonText: '取消',
        inputPlaceholder: '终止原因（选填）'
      }
    )
    remark = result.value.trim()
  } catch {
    return
  }
  try {
    await updateLeaseStatus(row.id, {
      status: 'TERMINATED',
      remark: remark || undefined
    })
    ElMessage.success('租住记录已终止')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '终止失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="lease-list">
    <div class="list-toolbar">
      <h2 class="list-title">租住记录</h2>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate">新建租住记录</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">状态</span>
      <el-select
        v-model="statusFilter"
        style="width: 140px"
        @change="handleFilterChange"
      >
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in leaseStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="leases" stripe>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column prop="residentName" label="居民" min-width="100" show-overflow-tooltip />
      <el-table-column prop="houseAddress" label="房屋" min-width="180" show-overflow-tooltip />
      <el-table-column label="租期" min-width="200">
        <template #default="{ row }">
          {{ formatDate(row.leaseStartDate) }} ~ {{ formatDate(row.leaseEndDate) }}
        </template>
      </el-table-column>
      <el-table-column label="月租金（元）" width="110" align="right">
        <template #default="{ row }">{{ row.monthlyRent }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag
            :label="leaseStatusLabels[row.status as LeaseStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button
            v-if="row.status !== 'TERMINATED'"
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            link
            type="danger"
            @click="handleTerminate(row)"
          >
            终止
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建租住记录' : '编辑租住记录'"
      width="560px"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="居民 ID" prop="residentId">
          <el-input-number
            v-model="form.residentId"
            :min="1"
            :controls="false"
            placeholder="居民账号 ID（居民列表可查）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="房屋 ID" prop="houseId">
          <el-input-number
            v-model="form.houseId"
            :min="1"
            :controls="false"
            placeholder="房屋 ID（房屋列表可查）"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="租期开始" prop="leaseStartDate">
          <el-date-picker
            v-model="form.leaseStartDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择开始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="租期结束" prop="leaseEndDate">
          <el-date-picker
            v-model="form.leaseEndDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="月租金" prop="monthlyRent">
          <el-input-number
            v-model="form.monthlyRent"
            :min="0"
            :controls="false"
            placeholder="元/月"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number
            v-model="form.depositAmount"
            :min="0"
            :controls="false"
            placeholder="选填，元"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="支付方式">
          <el-select v-model="form.paymentMethod" style="width: 100%">
            <el-option
              v-for="(label, value) in paymentMethodLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="合同编号">
          <el-input v-model="form.contractNumber" placeholder="选填" maxlength="64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            placeholder="选填"
            maxlength="200"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">保存</el-button>
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

.list-title {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
