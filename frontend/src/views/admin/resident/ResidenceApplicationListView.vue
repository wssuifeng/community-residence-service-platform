<script setup lang="ts">
/** 入住申请（「租住与房源」Hub 的入住申请 Tab，批次 g 由「居民」页迁入）：
 *  状态筛选 + 通过（租客只生成待签约租约）/ 驳回（接口设计.md 9.2.2.3 ~ 9.2.2.5） */
import { getCommunity } from '@/api/community'
import { contractEnd } from '@/utils/leaseTerms'
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import {
  approveResidenceApplication,
  getResidenceApplicationList,
  getResidenceApplication,
  rejectResidenceApplication
} from '@/api/resident'
import type {
  IResidenceApplication,
  ResidenceApplicationStatus
} from '@/types/modules/resident'
import { relationTypeLabels, residenceApplicationStatusLabels } from '@/types/modules/resident'
import { formatDateTime, todayISO } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

const emit = defineEmits<{ reviewed: [] }>()
const route = useRoute()
/* 占用入口指向明确申请，不按居民或房屋猜最新；隐藏的Hub面板不弹窗。 */
watch(() => [route.query.tab, route.query.applicationId], async ([tab, rawId]) => {
  const id = Number(rawId)
  if (tab !== 'applications' || !Number.isSafeInteger(id) || id <= 0) return
  try {
    const target = await getResidenceApplication(id)
    if (route.query.tab === 'applications' && Number(route.query.applicationId) === id && target.status === 'PENDING') openApprove(target)
  } catch { /* 统一请求层提示无权或已不存在，不退回猜测申请。 */ }
})


const records = ref<IResidenceApplication[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<ResidenceApplicationStatus | ''>('')

/** 申请状态 → StatusTag 语义色（已撤回为终态灰，不得落到驳回红） */
function statusTagType(
  status: ResidenceApplicationStatus
): 'pending' | 'completed' | 'rejected' | 'canceled' {
  if (status === 'PENDING') return 'pending'
  if (status === 'APPROVED') return 'completed'
  if (status === 'WITHDRAWN') return 'canceled'
  return 'rejected'
}

/* 申请身份 → 中文标签（后端 VO 为 relationType，非文档示例的 applicationType） */
function relationLabel(relationType?: string): string {
  return relationType
    ? relationTypeLabels[relationType as keyof typeof relationTypeLabels] ?? relationType
    : '—'
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

/* 租客审核只生成待签约租约；正式关系延后至首期成功。 */
const approveVisible = ref(false)
const approveLoading = ref(false)
const approveFormRef = ref<FormInstance>()
const approveTarget = ref<IResidenceApplication | null>(null)
const approveForm = reactive({
  contractMonths: 12, firstPaymentMonths: 1, paymentCycleMonths: 1,
  leaseStartDate: '',
  leaseEndDate: '',
  monthlyRent: undefined as number | undefined,
  depositAmount: undefined as number | undefined,
  remark: ''
})

watch(() => [approveForm.leaseStartDate, approveForm.contractMonths], () => { approveForm.leaseEndDate=contractEnd(approveForm.leaseStartDate,approveForm.contractMonths) })
const approveRules: FormRules = {
  leaseStartDate: [{ required: true, message: '请选择租约开始日期', trigger: 'change' }],
  leaseEndDate: [{ required: true, message: '请选择租约结束日期', trigger: 'change' }],
  monthlyRent: [{ required: true, message: '请输入月租金', trigger: 'blur' }]
}

function openApprove(row: IResidenceApplication): void {
  approveTarget.value = row
  approveFormRef.value?.resetFields()
  Object.assign(approveForm, {
    leaseStartDate: todayISO(),
    leaseEndDate: '', contractMonths: 12, firstPaymentMonths: 1, paymentCycleMonths: 1,
    monthlyRent: row.listedMonthlyRent ?? undefined,
    depositAmount: row.housingId ? (row.listedDeposit ?? 0) : undefined,
    remark: ''
  })
  approveForm.leaseEndDate=contractEnd(approveForm.leaseStartDate,approveForm.contractMonths)
  approveVisible.value = true
  void getCommunity(row.communityId).then(c=>{if(approveTarget.value?.id===row.id&&approveForm.contractMonths===12)approveForm.contractMonths=c.defaultLeaseMonths??12}).catch(()=>{})
}

async function handleApprove(): Promise<void> {
  if (!approveTarget.value) return
  const valid = await approveFormRef.value?.validate().catch(() => false)
  if (!valid) return
  approveLoading.value = true
  try {
    await approveResidenceApplication(approveTarget.value.id, {
      contractMonths: approveForm.contractMonths, firstPaymentMonths: approveForm.firstPaymentMonths, paymentCycleMonths: approveForm.paymentCycleMonths,
      leaseStartDate: approveForm.leaseStartDate,
      leaseEndDate: approveForm.leaseEndDate,
      monthlyRent: approveForm.monthlyRent as number,
      /* 后端 ApproveApplicationDTO 字段为 deposit（前端表单字段名保留 depositAmount） */
      deposit: approveForm.depositAmount,
      remark: approveForm.remark.trim() || undefined
    })
    ElMessage.success(approveTarget.value?.relationType === 'TENANT' ? '审批通过，已生成待签约租约；首期成功后正式入住' : '审批通过，已建立居住关系')
    approveVisible.value = false
    load()
    emit('reviewed')
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
      `驳回「${row.residentName}」申请入住「${row.houseLocation}」的申请，理由将通知申请人。`,
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
    emit('reviewed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '驳回失败')
  }
}

onMounted(async () => {
  await load()
  const id = Number(route.query.applicationId)
  if (route.query.tab === 'applications' && Number.isSafeInteger(id) && id > 0) {
    try { const target = await getResidenceApplication(id); if (route.query.tab === 'applications' && Number(route.query.applicationId) === id && target.status === 'PENDING') openApprove(target) } catch { /* 请求层统一提示。 */ }
  }
})
</script>

<template>
  <section class="application-list">
    <!-- 页标题由子视图承担（Hub 只提供 Tab 条，不再出现两层大标题） -->
    <AdminPageHeader
      title="入住申请"
      subtitle="居民入住申请审核 · 通过后落「待签约」租约并自动创建租约群聊"
    />

    <FilterPanel resettable @reset="handleReset">
      <el-select
        v-model="statusFilter"
        clearable
        placeholder="全部状态"
        class="status-select"
        @change="handleStatusChange"
      >
        <el-option
          v-for="(label, value) in residenceApplicationStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="records">
        <el-table-column prop="residentName" label="申请人" min-width="110" show-overflow-tooltip />
        <el-table-column label="身份" width="90">
          <template #default="{ row }">{{ relationLabel(row.relationType) }}</template>
        </el-table-column>
        <el-table-column prop="houseLocation" label="目标房屋" min-width="180" show-overflow-tooltip />
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
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="primary" size="small" @click="openApprove(row)">通过</el-button>
              <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="danger" size="small" @click="handleReject(row)">驳回</el-button>
            </template>
            <span v-else class="op-done">-</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无符合条件的申请" :image-size="80" />
        </template>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="load"
        @update:size="load"
      />
    </div>

    <!-- 审批通过：填写租住信息 -->
    <el-dialog v-model="approveVisible" title="审批通过" width="520px">
      <el-alert
        v-if="approveTarget"
        :title="approveTarget.relationType === 'TENANT' ? `通过后为「${approveTarget.residentName}」生成待签约租约，正式关系在协议确认且首期成功后建立` : `通过后为「${approveTarget.residentName}」建立居住关系`"
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
        <el-form-item label="合同总月数"><el-input-number v-model="approveForm.contractMonths" :min="1" :max="1200" /></el-form-item>
        <el-form-item label="首付月数"><el-input-number v-model="approveForm.firstPaymentMonths" :min="1" :max="approveForm.contractMonths" /></el-form-item>
        <el-form-item label="后续缴租周期"><el-input-number v-model="approveForm.paymentCycleMonths" :min="1" :max="approveForm.contractMonths" /><span>个月</span></el-form-item>
        <p>首期租金＋押金：{{ (approveForm.monthlyRent ?? 0) * approveForm.firstPaymentMonths + (approveForm.depositAmount ?? 0) }}元；交租不延长合同。</p>
        <el-form-item label="租约结束" prop="leaseEndDate">
          <el-date-picker
            v-model="approveForm.leaseEndDate" disabled
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="来源挂牌">
          <span>{{ approveTarget?.housingId ? `挂牌 #${approveTarget.housingId}` : '历史申请未记录来源，请人工核对金额' }}</span>
        </el-form-item>
        <el-form-item label="申请时价格">
          <span>{{ approveTarget?.listedMonthlyRent != null ? `月租 ¥${approveTarget.listedMonthlyRent} / 押金 ¥${approveTarget.listedDeposit ?? 0}` : '未记录，不按最新挂牌猜测' }}</span>
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
.status-select {
  width: 130px;
}

.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.op-done {
  color: var(--color-text-disabled);
}

.approve-tip {
  margin-bottom: var(--spacing-md);
}
</style>
