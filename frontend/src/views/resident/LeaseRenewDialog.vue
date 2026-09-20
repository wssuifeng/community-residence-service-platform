<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import {
  closePayment,
  createRenewPayment,
  getPaymentStatus,
  listPaymentChannels
} from '@/api/payment'
import { getLease } from '@/api/lease'
import type {
  ILeasePayment,
  ILeaseRecord,
  IPaymentChannelOption,
  PaymentChannel
} from '@/types/modules/lease'

/**
 * 续租支付弹窗（R61，v1.4）：三步流——①选月数与渠道（金额仅预览：月租×月数，
 * 实际金额由服务端计算，客户端不传金额，防篡改口径）→ ②渠道支付（支付宝整页表单
 * 写入新窗口提交 / 微信 Native code_url 渲染二维码）→ ③轮询确认（3s 一次、
 * 上限 5 分钟；本地环境无公网回调，以主动查单替代）。
 * 后端幂等：同租约已有进行中支付单时 createRenewPayment 返回既有单，
 * 月数/渠道/金额展示一律以返回单据为准。
 */

const props = defineProps<{
  /** 目标租约（弹窗打开期间由调用方保证非空） */
  lease: ILeaseRecord | null
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 支付成功（租期已延展），调用方据此刷新租约列表 */
  success: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

/* ------------------------------ 常量与展示映射 ------------------------------ */

const MONTH_PRESETS = [1, 3, 6, 12] as const
/** 轮询节奏：3 秒一次，累计上限 5 分钟（超时转失败态，真实结果由支付记录兜底可见） */
const POLL_INTERVAL_MS = 3000
const POLL_MAX_MS = 5 * 60 * 1000

const channelMeta: Record<PaymentChannel, { label: string; desc: string }> = {
  ALIPAY: { label: '支付宝', desc: '新窗口跳转支付宝完成付款' },
  WECHAT: { label: '微信支付', desc: '微信扫一扫二维码付款' }
}
const paymentChannelLabels: Record<PaymentChannel, string> = {
  ALIPAY: '支付宝',
  WECHAT: '微信支付'
}

function money(value: number | null | undefined): string {
  return value == null ? '—' : `¥ ${value.toLocaleString('zh-CN')}`
}

/** 止期顺延 N 月（月末溢出回夹到目标月最后一天），仅作回读失败时的展示兜底 */
function plusMonths(iso: string, months: number): string {
  const date = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(date.getTime())) return iso
  const day = date.getDate()
  date.setDate(1)
  date.setMonth(date.getMonth() + months)
  const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate()
  date.setDate(Math.min(day, lastDay))
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/* ------------------------------ 流程状态 ------------------------------ */

const step = ref<1 | 2 | 3>(1)
/** 终态视图（success/failed）覆盖三步展示 */
const result = ref<'none' | 'success' | 'failed'>('none')
const failReason = ref<'closed' | 'abandoned' | 'timeout' | ''>('')

const preset = ref<number | 'custom'>(3)
const customMonths = ref(1)
const channel = ref<PaymentChannel | null>(null)
const channelOptions = ref<IPaymentChannelOption[]>([])
const channelsLoading = ref(false)

const submitting = ref(false)
const closing = ref(false)
const payment = ref<ILeasePayment | null>(null)
/** 支付宝新窗口被浏览器拦截时置位，露出「重新打开支付页」手动入口 */
const alipayBlocked = ref(false)
const newEndDate = ref('')
const qrCanvas = ref<HTMLCanvasElement | null>(null)

const months = computed(() => (preset.value === 'custom' ? customMonths.value : preset.value))
const amountPreview = computed(() =>
  props.lease ? props.lease.monthlyRent * months.value : null
)

const failText = computed(() => {
  switch (failReason.value) {
    case 'closed':
      return '支付单已关闭，租期未变更'
    case 'abandoned':
      return '已放弃支付，租期未变更'
    case 'timeout':
      return '支付结果确认超时'
    default:
      return '支付未完成'
  }
})

/* ------------------------------ 渠道加载与选择 ------------------------------ */

async function loadChannels(): Promise<void> {
  channelsLoading.value = true
  try {
    const list = await listPaymentChannels()
    /* 双渠道恒渲染两卡：接口未返回的渠道按「未配置」处理（凭据门控口径） */
    channelOptions.value = (['ALIPAY', 'WECHAT'] as PaymentChannel[]).map((item) => ({
      channel: item,
      enabled: list.find((opt) => opt.channel === item)?.enabled ?? false
    }))
    const enabledOnly = channelOptions.value.filter((opt) => opt.enabled)
    if (enabledOnly.length === 1) channel.value = enabledOnly[0].channel
  } catch (error) {
    channelOptions.value = []
    ElMessage.error(error instanceof Error ? error.message : '支付渠道加载失败')
  } finally {
    channelsLoading.value = false
  }
}

function pickChannel(item: IPaymentChannelOption): void {
  if (!item.enabled) return
  channel.value = item.channel
}

/* ------------------------------ 提交支付单并分流 ------------------------------ */

async function submitPayment(): Promise<void> {
  if (submitting.value || !props.lease) return
  if (months.value < 1 || months.value > 36) {
    ElMessage.warning('续租月数需在 1~36 之间')
    return
  }
  if (!channel.value) {
    ElMessage.warning('请选择支付渠道')
    return
  }
  submitting.value = true
  try {
    /* 金额服务端计算；同租约已有进行中单时幂等返回该单 */
    const data = await createRenewPayment(props.lease.id, {
      months: months.value,
      channel: channel.value
    })
    payment.value = data
    if (data.status === 'SUCCESS') {
      /* 幂等命中已支付完成的单：直接收口成功态 */
      await settleSuccess()
      return
    }
    step.value = 2
    if (data.channel === 'ALIPAY') {
      if (data.alipayForm) openAlipay(data.alipayForm)
    } else if (data.qrCode) {
      await nextTick()
      await renderQr(data.qrCode)
    }
    startPolling()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建支付单失败')
  } finally {
    submitting.value = false
  }
}

/** 支付宝整页表单提交：写入新窗口并自动 submit；被拦截时置标记供手动重开 */
function openAlipay(formHtml: string): void {
  const win = window.open('', '_blank')
  if (!win) {
    alipayBlocked.value = true
    return
  }
  alipayBlocked.value = false
  win.document.open()
  win.document.write(formHtml)
  win.document.close()
  try {
    win.document.forms[0]?.submit()
  } catch {
    /* 表单自带提交按钮，新窗口内可手动点击兜底 */
  }
}

function reopenAlipay(): void {
  const form = payment.value?.alipayForm
  if (form) openAlipay(form)
}

async function renderQr(text: string): Promise<void> {
  const canvas = qrCanvas.value
  if (!canvas) return
  try {
    await QRCode.toCanvas(canvas, text, { width: 220, margin: 2 })
  } catch {
    ElMessage.error('支付二维码渲染失败，可放弃后重新发起')
  }
}

/* ------------------------------ 轮询确认（3s 一次，上限 5 分钟） ------------------------------ */

let pollTimer: number | undefined
let pollDeadline = 0

function stopPolling(): void {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

function startPolling(): void {
  stopPolling()
  pollDeadline = Date.now() + POLL_MAX_MS
  void pollOnce()
  pollTimer = window.setInterval(() => void pollOnce(), POLL_INTERVAL_MS)
}

async function pollOnce(): Promise<void> {
  const current = payment.value
  if (!current) return
  if (Date.now() >= pollDeadline) {
    stopPolling()
    failReason.value = 'timeout'
    result.value = 'failed'
    return
  }
  try {
    const latest = await getPaymentStatus(current.paymentNo)
    /* 重试可能已换新单，丢弃旧单迟到的响应 */
    if (payment.value?.paymentNo !== latest.paymentNo) return
    payment.value = latest
    if (latest.status === 'SUCCESS') {
      await settleSuccess()
    } else if (latest.status === 'CLOSED') {
      stopPolling()
      failReason.value = 'closed'
      result.value = 'failed'
    }
  } catch {
    /* 单次查询失败不打断轮询，等待下一轮 */
  }
}

function gotoPolling(): void {
  step.value = 3
  void pollOnce()
}

async function settleSuccess(): Promise<void> {
  stopPolling()
  result.value = 'success'
  /* 新止期以服务端回读为准（后端已延展 end_date）；回读失败回落本地按月推算 */
  const leaseId = props.lease?.id
  const paidMonths = payment.value?.months ?? months.value
  if (leaseId != null && props.lease) {
    try {
      const fresh = await getLease(leaseId)
      newEndDate.value = fresh.endDate
    } catch {
      newEndDate.value = plusMonths(props.lease.endDate, paidMonths)
    }
  }
  emit('success')
}

/** 放付支付：关闭未支付单；关闭被拒（常见于刚支付完成）时立即查单以真实状态收口 */
async function abandonPayment(): Promise<void> {
  const current = payment.value
  if (!current || closing.value) return
  closing.value = true
  try {
    await closePayment(current.paymentNo)
    stopPolling()
    failReason.value = 'abandoned'
    result.value = 'failed'
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '关闭支付单失败')
    void pollOnce()
  } finally {
    closing.value = false
  }
}

/** 失败后重新发起：回第 1 步重选（后端幂等，进行中单会被原样返回） */
function retryFromStart(): void {
  result.value = 'none'
  failReason.value = ''
  step.value = 1
  payment.value = null
  alipayBlocked.value = false
}

/* ------------------------------ 弹窗生命周期 ------------------------------ */

function resetDialog(): void {
  step.value = 1
  result.value = 'none'
  failReason.value = ''
  preset.value = 3
  customMonths.value = 1
  channel.value = null
  payment.value = null
  alipayBlocked.value = false
  newEndDate.value = ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetDialog()
      void loadChannels()
    } else {
      /* 关闭仅停轮询：进行中支付单保留，重开续租时幂等续接 */
      stopPolling()
    }
  }
)

onUnmounted(stopPolling)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`续租 · ${lease?.houseLocation ?? ''}`"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-steps v-if="result === 'none'" :active="step - 1" simple class="flow-steps">
      <el-step title="选月数渠道" />
      <el-step title="支付" />
      <el-step title="确认结果" />
    </el-steps>

    <!-- 第一步：月数 + 渠道 + 金额预览 -->
    <div v-if="result === 'none' && step === 1" v-loading="channelsLoading" class="step-body">
      <dl class="lease-brief">
        <div class="brief-house">
          <dt>房屋</dt>
          <dd>{{ lease?.houseLocation || '—' }}</dd>
        </div>
        <div>
          <dt>当前租期</dt>
          <dd class="mono">{{ lease ? `${lease.startDate} ~ ${lease.endDate}` : '—' }}</dd>
        </div>
        <div>
          <dt>月租</dt>
          <dd>{{ lease ? money(lease.monthlyRent) : '—' }}</dd>
        </div>
      </dl>

      <div class="field">
        <p class="field-label">续租月数（1~36 个月）</p>
        <div class="months-row">
          <el-radio-group v-model="preset">
            <el-radio-button v-for="m in MONTH_PRESETS" :key="m" :value="m">
              {{ m }} 个月
            </el-radio-button>
            <el-radio-button value="custom">自定义</el-radio-button>
          </el-radio-group>
          <el-input-number
            v-if="preset === 'custom'"
            v-model="customMonths"
            :min="1"
            :max="36"
            style="width: 120px"
          />
        </div>
      </div>

      <div class="field">
        <p class="field-label">支付渠道</p>
        <div class="channel-grid">
          <button
            v-for="opt in channelOptions"
            :key="opt.channel"
            type="button"
            class="channel-card"
            :class="{
              'is-selected': channel === opt.channel,
              'is-disabled': !opt.enabled
            }"
            :disabled="!opt.enabled"
            @click="pickChannel(opt)"
          >
            <span class="channel-icon" :data-channel="opt.channel">
              {{ opt.channel === 'ALIPAY' ? '支' : '微' }}
            </span>
            <span class="channel-body">
              <span class="channel-name">{{ channelMeta[opt.channel].label }}</span>
              <span class="channel-desc">{{ channelMeta[opt.channel].desc }}</span>
            </span>
            <span v-if="!opt.enabled" class="channel-badge">未配置</span>
          </button>
        </div>
        <p v-if="!channelsLoading && channelOptions.length === 0" class="channel-empty">
          支付渠道加载失败，请关闭弹窗后重试
        </p>
      </div>

      <div class="amount-preview">
        <span class="amount-label">金额预览</span>
        <span class="amount-value">{{ money(amountPreview) }}</span>
        <span class="amount-note">
          = {{ money(lease?.monthlyRent) }} × {{ months }} 个月，实际以支付页为准
        </span>
      </div>
    </div>

    <!-- 第二步：渠道支付（支付宝新窗口 / 微信二维码） -->
    <div v-else-if="result === 'none' && step === 2 && payment" class="step-body">
      <p class="pay-meta-line">
        <span class="mono">单号 {{ payment.paymentNo }}</span>
        <span>{{ paymentChannelLabels[payment.channel] }}</span>
        <span>{{ money(payment.amount) }}</span>
        <span>续租 {{ payment.months }} 个月</span>
      </p>

      <template v-if="payment.channel === 'ALIPAY'">
        <div v-if="!alipayBlocked" class="pay-guide">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
            <polyline points="15 3 21 3 21 9" />
            <line x1="10" y1="14" x2="21" y2="3" />
          </svg>
          已在新窗口打开支付宝，请在支付宝页面完成付款
        </div>
        <div v-else class="pay-blocked">
          浏览器拦截了弹出新窗口，请点击下方「重新打开支付页」前往支付宝
        </div>
      </template>
      <template v-else>
        <div v-if="payment.qrCode" class="qr-area">
          <canvas ref="qrCanvas" class="qr-canvas" width="220" height="220"></canvas>
          <p class="qr-hint">请使用微信扫码支付</p>
        </div>
        <div v-else class="pay-guide">二维码暂不可用，请稍候确认或放弃后重新发起</div>
      </template>

      <p class="pay-note">完成支付后本页自动确认结果，也可点击「我已完成支付」立即查询</p>
    </div>

    <!-- 第三步：等待确认 -->
    <div v-else-if="result === 'none' && step === 3 && payment" class="step-body">
      <div class="poll-box">
        <span class="poll-spinner" aria-hidden="true"></span>
        <p class="poll-title">等待支付结果…</p>
        <p class="poll-sub">每 3 秒确认一次，最长等待 5 分钟</p>
        <p class="poll-sub mono">支付单号 {{ payment.paymentNo }}</p>
      </div>
    </div>

    <!-- 终态：成功 -->
    <div v-else-if="result === 'success'" class="step-body">
      <div class="result-box is-success">
        <span class="result-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20 6 9 17l-5-5" />
          </svg>
        </span>
        <p class="result-title">续租成功，租期已延展</p>
        <p class="result-sub">
          新止期 <strong class="mono">{{ newEndDate || '—' }}</strong>（{{ payment?.months ?? months }} 个月）
        </p>
        <p v-if="payment" class="result-sub mono">支付单号 {{ payment.paymentNo }}</p>
      </div>
    </div>

    <!-- 终态：失败 -->
    <div v-else-if="result === 'failed'" class="step-body">
      <div class="result-box is-failed">
        <span class="result-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </span>
        <p class="result-title">{{ failText }}</p>
        <p v-if="failReason === 'timeout'" class="result-sub">
          如刚完成支付，结果会稍后同步，可在「支付记录」中查看
        </p>
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="result === 'success'">
          <el-button type="primary" round @click="visible = false">完成</el-button>
        </template>
        <template v-else-if="result === 'failed'">
          <el-button round @click="visible = false">关闭</el-button>
          <el-button type="primary" round @click="retryFromStart">重新发起</el-button>
        </template>
        <template v-else-if="step === 1">
          <el-button round @click="visible = false">取消</el-button>
          <el-button
            type="primary"
            round
            :loading="submitting"
            :disabled="submitting || channelsLoading"
            @click="submitPayment"
          >
            去支付
          </el-button>
        </template>
        <template v-else-if="step === 2">
          <el-button round :loading="closing" :disabled="closing" @click="abandonPayment">
            放弃支付
          </el-button>
          <el-button
            v-if="payment?.channel === 'ALIPAY' && alipayBlocked"
            type="warning"
            plain
            round
            @click="reopenAlipay"
          >
            重新打开支付页
          </el-button>
          <el-button type="primary" round @click="gotoPolling">我已完成支付</el-button>
        </template>
        <template v-else>
          <el-button round :loading="closing" :disabled="closing" @click="abandonPayment">
            放弃支付
          </el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.flow-steps {
  margin-bottom: var(--spacing-lg);
}

.step-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 260px;
}

.mono {
  font-family: var(--font-family-mono);
}

/* 第一步：租约摘要 */
.lease-brief {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm) var(--spacing-md);
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
}

.brief-house {
  grid-column: 1 / -1;
}

.lease-brief dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: 2px;
}

.lease-brief dd {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

/* 第一步：字段区 */
.field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.field-label {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.months-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

/* 渠道两卡：可点选中，未配置禁用置灰 */
.channel-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm);
}

.channel-card {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.channel-card:not(.is-disabled):hover {
  border-color: var(--color-primary);
}

.channel-card.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
  box-shadow: var(--shadow-sm);
}

.channel-card.is-disabled {
  background: var(--color-bg);
  cursor: not-allowed;
  opacity: 0.6;
}

.channel-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--radius-sm);
  color: #fff;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
}

/* 渠道品牌色：支付宝蓝 / 微信绿 */
.channel-icon[data-channel='ALIPAY'] {
  background: #1677ff;
}

.channel-icon[data-channel='WECHAT'] {
  background: #07c160;
}

.channel-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.channel-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.channel-card.is-disabled .channel-name {
  color: var(--color-text-secondary);
}

.channel-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.channel-badge {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 1px 6px;
  border-radius: var(--radius-pill);
  font-size: 10px;
  line-height: 1.5;
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.channel-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

/* 金额预览条 */
.amount-preview {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) 0 0;
  border-top: 1px dashed var(--color-border);
}

.amount-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.amount-value {
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
  font-family: var(--font-family-mono);
}

.amount-note {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 第二步：单据摘要 + 支付引导 */
.pay-meta-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  padding: var(--spacing-xs) var(--spacing-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.pay-guide {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  flex: 1;
  padding: var(--spacing-lg);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  text-align: center;
}

.pay-guide svg {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  color: var(--color-primary);
}

.pay-blocked {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--spacing-lg);
  border-radius: var(--radius-md);
  background: rgba(245, 158, 11, 0.12);
  color: var(--color-warning);
  font-size: var(--font-size-sm);
  text-align: center;
}

.qr-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  flex: 1;
  justify-content: center;
}

.qr-canvas {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.qr-hint {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.pay-note {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: center;
}

/* 第三步：等待确认 */
.poll-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg) 0;
}

.poll-spinner {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
  border: 3px solid var(--color-primary-bg);
  border-top-color: var(--color-primary);
  animation: poll-rotate 0.9s linear infinite;
}

@keyframes poll-rotate {
  to {
    transform: rotate(360deg);
  }
}

.poll-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.poll-sub {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 终态 */
.result-box {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-lg) 0;
}

.result-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-circle);
}

.result-icon svg {
  width: 22px;
  height: 22px;
}

.result-box.is-success .result-icon {
  background: rgba(16, 185, 129, 0.12);
  color: var(--color-success);
}

.result-box.is-failed .result-icon {
  background: rgba(239, 68, 68, 0.1);
  color: var(--color-danger);
}

.result-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.result-sub {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
}

@media (max-width: 600px) {
  .channel-grid {
    grid-template-columns: 1fr;
  }
}
</style>
