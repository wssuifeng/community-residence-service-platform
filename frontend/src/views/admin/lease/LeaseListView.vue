<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createLease,
  getExpiringLeaseList,
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

/** 租住管理：全部记录 + 即将到期两个 Tab */
const activeTab = ref<'all' | 'expiring'>('all')

const leases = ref<ILeaseRecord[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<LeaseStatus | ''>('')

/* 即将到期 Tab 独立状态 */
const expiringLeases = ref<ILeaseRecord[]>([])
const expiringLoading = ref(false)
const expiringPage = ref(1)
const expiringSize = ref(10)
const expiringTotal = ref(0)
const daysWindow = ref(30)

/** 租住状态 → StatusTag 语义色（生效 completed / 到期、终止 canceled） */
function statusTagType(status: LeaseStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

/* 即将到期 Tab 辅助 */
function remainingDays(endDate: string): number {
  const end = new Date(`${formatDate(endDate)}T00:00:00`).getTime()
  const today = new Date(`${formatDate(new Date().toISOString())}T00:00:00`).getTime()
  return Math.round((end - today) / 86400000)
}

function remainingText(days: number): string {
  if (days < 0) return `已逾期 ${Math.abs(days)} 天`
  if (days === 0) return '今日到期'
  return `剩 ${days} 天`
}

const expiringSummary = computed(() => {
  const overdue = expiringLeases.value.filter((item) => remainingDays(item.leaseEndDate) < 0).length
  return `共 ${expiringTotal.value} 条即将到期${overdue > 0 ? `，其中已逾期 ${overdue} 条` : ''}`
})

async function loadExpiring(): Promise<void> {
  expiringLoading.value = true
  try {
    const result = await getExpiringLeaseList({
      page: expiringPage.value,
      size: expiringSize.value,
      days: daysWindow.value
    })
    expiringLeases.value = [...result.records].sort(
      (a, b) => new Date(a.leaseEndDate).getTime() - new Date(b.leaseEndDate).getTime()
    )
    expiringTotal.value = result.total
  } catch {
    expiringLeases.value = []
    expiringTotal.value = 0
  } finally {
    expiringLoading.value = false
  }
}

function handleExpiringFilterChange(): void {
  expiringPage.value = 1
  loadExpiring()
}

function handleExpiringReset(): void {
  daysWindow.value = 30
  expiringPage.value = 1
  loadExpiring()
}

/* 切换 Tab 时懒加载即将到期数据 */
watch(activeTab, (tab) => {
  if (tab === 'expiring' && expiringLeases.value.length === 0) loadExpiring()
})

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
      <el-button
        v-if="activeTab === 'all'"
        v-permission="['ADMIN', 'SUPER_ADMIN']"
        type="primary"
        @click="openCreate"
      >
        新建租住记录
      </el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="全部记录" name="all">
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
      </el-tab-pane>

      <el-tab-pane label="即将到期" name="expiring">
        <div class="tab-summary">{{ expiringSummary }}</div>

        <FilterPanel resettable @reset="handleExpiringReset">
          <span class="filter-label">到期窗口</span>
          <el-select
            v-model="daysWindow"
            style="width: 160px"
            @change="handleExpiringFilterChange"
          >
            <el-option label="未来 7 天" :value="7" />
            <el-option label="未来 15 天" :value="15" />
            <el-option label="未来 30 天" :value="30" />
            <el-option label="未来 60 天" :value="60" />
            <el-option label="未来 90 天" :value="90" />
          </el-select>
        </FilterPanel>

        <el-table v-loading="expiringLoading" :data="expiringLeases" stripe>
          <el-table-column prop="id" label="ID" width="64" />
          <el-table-column prop="residentName" label="居民" min-width="100" show-overflow-tooltip />
          <el-table-column prop="houseAddress" label="房屋" min-width="180" show-overflow-tooltip />
          <el-table-column label="租期" min-width="200">
            <template #default="{ row }">
              {{ formatDate(row.leaseStartDate) }} ~ {{ formatDate(row.leaseEndDate) }}
            </template>
          </el-table-column>
          <el-table-column label="到期日" width="110" sortable sort-by="leaseEndDate">
            <template #default="{ row }">{{ formatDate(row.leaseEndDate) }}</template>
          </el-table-column>
          <el-table-column label="剩余天数" width="120">
            <template #default="{ row }">
              <span
                :style="{
                  color:
                    remainingDays(row.leaseEndDate) < 0
                      ? 'var(--color-danger)'
                      : remainingDays(row.leaseEndDate) < 30
                        ? 'var(--color-warning)'
                        : 'var(--color-text-primary)'
                }"
              >
                {{ remainingText(remainingDays(row.leaseEndDate)) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <StatusTag
                :label="leaseStatusLabels[row.status as LeaseStatus]"
                :type="statusTagType(row.status)"
              />
            </template>
          </el-table-column>
        </el-table>

        <Pagination
          v-model:page="expiringPage"
          v-model:size="expiringSize"
          :total="expiringTotal"
          @update:page="loadExpiring"
          @update:size="loadExpiring"
        />
      </el-tab-pane>
    </el-tabs>

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

.tab-summary {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  margin-bottom: var(--spacing-sm);
}
</style>
