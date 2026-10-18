<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createDepositRefund, listDepositRefunds } from '@/common/lease/api/lease'
import type { DepositRefundStatus, IDepositRefund, ILeasePayment, ILeaseRecord } from '@/common/lease/types/lease'
import { depositRefundStatusLabels, depositStatusLabels } from '@/common/lease/types/lease'
import { formatDateTime } from '@/common/web/utils/date'

/**
 * 发起押金退还弹窗（R72，管理端专属；接口设计 §9.20.1）。
 *
 * 契约约束（逐条落到交互，不靠后端报错兜底才提示）：
 * - **渠道只读 = 原路退回**：服务端取该租约实缴支付单（`payment_type='FIRST'` 且成功）的渠道，
 *   请求体不接受 channel，故这里只**展示**原渠道，不提供选择控件；
 * - **金额默认全额、可改但 ≤ 实缴可退金额**：默认留空提交（空 = 服务端按
 *   「实缴押金 − 已成功退还金额」全额退还）；手填时前端先行拦截 > 可退金额（后端 5704 复检）；
 * - **幂等键 `outRequestNo`**：打开弹窗时生成一次并复用（同一笔退还重复提交返回既有记录）；
 *   **渠道失败重试必须换新号**（§9.20.1 明文），故重试路径显式重新生成；
 * - **失败原因可见**：渠道失败（含凭据未配置 5901）一律把后端 message 呈现在弹窗内，
 *   而不是只弹一条转眼即逝的 toast。
 */

const props = defineProps<{
  /** 目标租约（弹窗打开期间由调用方保证非空；仅 MOVED_OUT 且押金已缴可发起） */
  lease: ILeaseRecord | null
  /**
   * 该租约的支付单（**必传**）：用于推导「实缴押金」与原支付渠道。
   * 空数组 = 尚无成功支付单 —— 此时渠道与实缴金额未知，弹窗禁用提交并明示原因。
   */
  firstPayments: ILeasePayment[]
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /**
   * 发起成功（PENDING/SUCCESS）时通知父层刷新押金状态与流水（OCR DEF-123）：
   * FAILED 停留弹窗重试，不 emit——父层按「成功」刷新会把回落 PAID 的租约误标「退还中」。
   */
  success: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

function money(value: number | null | undefined): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

/* ------------------------------ 原渠道与实缴押金推导 ------------------------------ */

/**
 * 支付单来源（父层传入）：实缴押金与原支付渠道的推导依据。
 * 组件不做自取——押金退还的调用方（租约详情、退租申请审核）都已有该租约的支付单，
 * 由父层传入可避免同一端点两条取数链（且父层的租约数据先于弹窗就绪）。
 */
const effectivePayments = computed<ILeasePayment[]>(() => props.firstPayments ?? [])

/** 实缴首期支付单：决定原支付渠道与实缴押金口径（§9.20.1：实缴 = 成功 FIRST 单的押金部分） */
const paidFirstPayment = computed<ILeasePayment | null>(() => {
  return (
    effectivePayments.value.find(
      (item) => item.paymentType === 'FIRST' && item.status === 'SUCCESS'
    ) ?? null
  )
})

/** 原支付渠道：只读展示；取不到（无成功首期单）时提示无法退款而非让管理方瞎猜 */
const originalChannel = computed(() => paidFirstPayment.value?.channel ?? null)

const channelLabel = computed(() => {
  if (originalChannel.value === 'ALIPAY') return '支付宝'
  if (originalChannel.value === 'WECHAT') return '微信支付'
  return '未知'
})

/**
 * 实缴押金：优先取成功首期单的 `depositAmount`（R73 扩展字段）；
 * 缺失时回落首期单总额 − 租金部分，再回落租约登记押金（口径兜底，仍由服务端最终判定）。
 */
const paidDeposit = computed<number | null>(() => {
  const payment = paidFirstPayment.value
  if (!payment) return props.lease?.deposit ?? null
  if (payment.depositAmount != null) return payment.depositAmount
  if (payment.rentAmount != null && payment.amount != null) {
    return Math.max(0, payment.amount - payment.rentAmount)
  }
  return props.lease?.deposit ?? null
})

/** 可退余额 = 实缴押金 − 已成功退还金额（在下方 refundedAmount 处按实时流水计算） */

/* ------------------------------ 表单与幂等键 ------------------------------ */

/**
 * 幂等键（商户退款请求号，≤64）：`DR` + 租约 ID + 时间戳 + 4 位随机。
 * 弹窗打开时生成一次；**失败重试重新生成**（§9.20.1：重试须换新的 outRequestNo）。
 */
function nextOutRequestNo(leaseId: number | undefined): string {
  const stamp = Date.now().toString(36).toUpperCase()
  const rand = Math.random().toString(36).slice(2, 6).toUpperCase()
  return `DR${leaseId ?? 0}${stamp}${rand}`.slice(0, 64)
}

const form = reactive<{ amount: number | undefined; remark: string }>({
  amount: undefined,
  remark: ''
})
const outRequestNo = ref('')

const submitting = ref(false)
/** 本次提交结果：成功回执 / 失败原因（失败原因必须留在页面上可见） */
const result = ref<IDepositRefund | null>(null)
const failMessage = ref('')

const amountError = computed(() => {
  const value = form.amount
  if (value == null) return ''
  if (value <= 0) return '退还金额须大于 0'
  if (refundable.value != null && value > refundable.value) {
    return `退还金额不得超过实缴可退金额 ${money(refundable.value)}`
  }
  return ''
})

const canSubmit = computed(
  () =>
    !submitting.value &&
    originalChannel.value !== null &&
    amountError.value === '' &&
    (refundable.value == null || refundable.value > 0)
)

function reset(): void {
  form.amount = undefined
  form.remark = ''
  outRequestNo.value = nextOutRequestNo(props.lease?.id)
  result.value = null
  failMessage.value = ''
}

/** 押金状态中文标签（OCR DEF-123）：全五态映射（未缴/已缴/退还中/已退还/已抵扣），
 *  未知/缺失回落「未缴」占位——不再裸出英文枚举值 */
const depositStatusText = computed(() => {
  const status = props.lease?.depositStatus
  return status ? (depositStatusLabels[status] ?? status) : '未缴'
})

async function handleSubmit(): Promise<void> {
  const lease = props.lease
  if (!lease) return
  if (amountError.value) {
    ElMessage.warning(amountError.value)
    return
  }
  submitting.value = true
  failMessage.value = ''
  try {
    /* amount 留空 = 服务端全额退还；不传 channel（原路退回由服务端判定） */
    const created = await createDepositRefund(lease.id, {
      amount: form.amount ?? undefined,
      remark: form.remark.trim() || undefined,
      outRequestNo: outRequestNo.value
    })
    result.value = created
    if (created.status === 'SUCCESS') {
      ElMessage.success('押金已原路退还成功')
      emit('success')
    } else if (created.status === 'PENDING') {
      ElMessage.success('押金退还指令已提交，等待渠道结果')
      emit('success')
    } else {
      /* 渠道失败（OCR DEF-123）：原因留在弹窗内（覆盖 toast 一闪而过的问题），
         停留弹窗等管理方换号重试，不 emit success 通知父层 */
      failMessage.value = created.failReason || '渠道退款失败，请稍后重试'
    }
  } catch (error) {
    failMessage.value = error instanceof Error ? error.message : '押金退还发起失败'
  } finally {
    submitting.value = false
  }
}

/** 失败重试：**换新幂等键**后重新提交（§9.20.1 明文要求），金额与备注保留供核对 */
function handleRetry(): void {
  outRequestNo.value = nextOutRequestNo(props.lease?.id)
  result.value = null
  failMessage.value = ''
  void handleSubmit()
}

const RESULT_TAG: Record<DepositRefundStatus, string> = {
  PENDING: 'pending',
  SUCCESS: 'completed',
  FAILED: 'failed'
}

/**
 * 展示用的退还流水：回读该租约历史流水（§9.20.2）。
 * 用途有二：① 推导「已成功退还金额」，把可退余额算准（不依赖父层传入）；
 * ② 失败重试的上下文——管理方能看到上一次为什么失败，再决定是否换号重试。
 */
const history = ref<IDepositRefund[]>([])
const historyLoading = ref(false)

/**
 * 已成功退还金额：只累计 `SUCCESS` 的单——`FAILED` 表示渠道退款未成功，
 * 金额并未退回，计入会把可退余额算少并误导管理方。
 */
const refundedAmount = computed(() =>
  history.value
    .filter((item) => item.status === 'SUCCESS')
    .reduce((sum, item) => sum + (item.amount ?? 0), 0)
)

/** 可退余额 = 实缴押金 − 已成功退还金额（不为负） */
const refundable = computed<number | null>(() => {
  const paid = paidDeposit.value
  if (paid == null) return null
  return Math.max(0, paid - refundedAmount.value)
})

async function loadHistory(): Promise<void> {
  const lease = props.lease
  if (!lease) {
    history.value = []
    return
  }
  historyLoading.value = true
  try {
    history.value = await listDepositRefunds(lease.id)
  } catch {
    /* 历史流水取不到不影响本次发起：可退余额退化为「以实缴押金为上限」，由服务端最终判定 */
    history.value = []
  } finally {
    historyLoading.value = false
  }
  /* 金额默认全额（可改）：在可退余额算出来之后预填，管理方一眼看到默认值而非空框 */
  if (form.amount == null && refundable.value != null && refundable.value > 0) {
    form.amount = refundable.value
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      reset()
      void loadHistory()
    }
  }
)
</script>

<template>
  <el-dialog v-model="visible" title="发起押金退还" width="560px" :close-on-click-modal="false">
    <template v-if="lease">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="form-alert"
        title="原渠道原路退回，平台不托管押金资金"
        description="渠道由系统按该租约实缴支付单原路退回，不可指定；平台不托管押金资金、不计息、不垫付。"
      />

      <dl class="info-rows">
        <div class="info-row">
          <dt>租约</dt>
          <dd>ZL{{ String(lease.id).padStart(4, '0') }} · {{ lease.houseLocation }}</dd>
        </div>
        <div class="info-row">
          <dt>押金状态</dt>
          <!-- 全五态中文标签（OCR DEF-123），不裸出英文枚举值 -->
          <dd>{{ depositStatusText }}</dd>
        </div>
        <div class="info-row">
          <dt>实缴押金</dt>
          <dd class="mono">{{ money(paidDeposit) }}</dd>
        </div>
        <div class="info-row">
          <dt>已退还</dt>
          <dd class="mono">{{ money(refundedAmount) }}</dd>
        </div>
        <div class="info-row">
          <dt>可退金额</dt>
          <dd class="mono strong">{{ money(refundable) }}</dd>
        </div>
      </dl>

      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="退款渠道">
          <!-- 渠道只读：原路退回，服务端取实缴渠道，客户端不可指定（§9.20.1） -->
          <el-input :model-value="channelLabel" readonly>
            <template #prefix>
              <span class="channel-dot" :data-channel="originalChannel" aria-hidden="true"></span>
            </template>
          </el-input>
          <p class="field-hint">
            {{ originalChannel
              ? `只读：按实缴支付单原路退回至${channelLabel}，不可跨渠道退款`
              : '未查到成功首期支付单，无法确定实缴渠道，暂不可发起退还' }}
          </p>
        </el-form-item>

        <el-form-item label="退还金额">
          <el-input-number
            v-model="form.amount"
            :min="0"
            :controls="false"
            :placeholder="refundable != null ? `留空=全额退还 ${money(refundable)}` : '留空=全额退还'"
            style="width: 100%"
          />
          <p v-if="amountError" class="field-error">{{ amountError }}</p>
          <p v-else class="field-hint">留空表示全额退还；手填不得超过实缴可退金额</p>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="选填，如押金抵扣说明、线下沟通结论"
          />
        </el-form-item>

        <el-form-item label="幂等请求号">
          <span class="mono req-no">{{ outRequestNo }}</span>
          <p class="field-hint">失败重试会自动换新请求号（同一请求号重复提交返回既有记录）</p>
        </el-form-item>
      </el-form>

      <!-- 本次提交结果：成功/进行中回执，失败原因常驻可见并可直接重试 -->
      <div v-if="result && result.status !== 'FAILED'" class="result-box" :data-status="RESULT_TAG[result.status]">
        <p class="result-title">押金退还{{ depositRefundStatusLabels[result.status] }}</p>
        <p class="result-line">
          {{ money(result.amount) }} · {{ result.channel === 'ALIPAY' ? '支付宝' : '微信支付' }}
          <template v-if="result.channelRefundNo"> · 渠道退款单号 <span class="mono">{{ result.channelRefundNo }}</span></template>
        </p>
        <p class="result-line mono">请求号 {{ result.outRequestNo }}</p>
      </div>

      <div v-if="failMessage" class="fail-box" role="alert">
        <p class="result-title">押金退还失败</p>
        <p class="result-line">{{ failMessage }}</p>
        <el-button type="warning" plain size="small" :loading="submitting" @click="handleRetry">
          换新请求号重试
        </el-button>
      </div>

      <!-- 历史退还流水：管理方发起前可核对上一次结果与失败原因 -->
      <el-collapse v-if="history.length > 0" class="history-collapse">
        <el-collapse-item :title="`历史退还记录（${history.length}）`" name="history">
          <ul v-loading="historyLoading" class="history-list">
            <li v-for="item in history" :key="item.id" class="history-item">
              <span class="history-amount mono">{{ money(item.amount) }}</span>
              <span class="history-channel">{{ item.channel === 'ALIPAY' ? '支付宝' : '微信支付' }}</span>
              <span class="history-status" :data-status="item.status">
                {{ depositRefundStatusLabels[item.status] }}
              </span>
              <span class="history-time">{{ formatDateTime(item.refundedAt ?? item.createdAt) }}</span>
              <p v-if="item.failReason" class="history-fail">失败原因：{{ item.failReason }}</p>
            </li>
          </ul>
        </el-collapse-item>
      </el-collapse>
    </template>

    <!-- 租约信息缺失（OCR DEF-123）：显式错误文案替代空白弹窗，不让管理方对着空壳猜原因 -->
    <template v-else>
      <el-alert
        type="error"
        :closable="false"
        show-icon
        class="form-alert"
        title="租约信息加载失败，请刷新重试"
        description="未能获取该租约的详情（网络异常或租约已不存在），关闭弹窗后重新打开即可再次发起押金退还。"
      />
    </template>

    <template #footer>
      <el-button @click="visible = false">{{ result ? '关闭' : '取消' }}</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="handleSubmit">
        确认发起退还
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-alert {
  margin-bottom: var(--spacing-md);
}

.info-rows {
  margin: 0 0 var(--spacing-md);
  padding: 0;
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) 0;
  border-bottom: 1px dashed var(--color-border);
}

.info-row:last-child {
  border-bottom: none;
}

.info-row dt {
  flex-shrink: 0;
  width: 88px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.info-row dd {
  margin: 0;
  min-width: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-word;
}

.strong {
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.mono {
  font-family: var(--font-family-mono);
}

/* 渠道只读指示点：支付宝蓝 / 微信绿 / 未知灰 */
.channel-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: var(--radius-circle);
  background: var(--color-text-disabled);
}

.channel-dot[data-channel='ALIPAY'] {
  background: #1677ff;
}

.channel-dot[data-channel='WECHAT'] {
  background: #07c160;
}

.field-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.field-error {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.req-no {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

/* 结果盒：成功绿 / 进行中橙 */
.result-box,
.fail-box {
  margin-top: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
}

.result-box[data-status='completed'] {
  background: var(--color-success-soft);
}

.result-box[data-status='pending'] {
  background: var(--color-warning-soft);
}

.fail-box {
  background: var(--color-danger-soft);
}

.result-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.result-line {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.fail-box .result-line {
  color: var(--color-danger);
}

.history-collapse {
  margin-top: var(--spacing-md);
}

.history-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.history-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-xs) 0;
  border-bottom: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.history-item:last-child {
  border-bottom: none;
}

.history-amount {
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.history-status[data-status='SUCCESS'] {
  color: var(--color-success);
}

.history-status[data-status='PENDING'] {
  color: var(--color-warning);
}

.history-status[data-status='FAILED'] {
  color: var(--color-danger);
}

.history-fail {
  flex-basis: 100%;
  margin: 2px 0 0;
  color: var(--color-danger);
}
</style>
