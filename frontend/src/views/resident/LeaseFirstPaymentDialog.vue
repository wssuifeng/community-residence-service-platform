<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import { getRentQuote, closePayment, createFirstPayment, getPaymentStatus, listPaymentChannels } from '@/api/payment'
import { listLeasePayments } from '@/api/lease'
import { ApiError } from '@/utils/request'
import { formatDateTime, formatDateTimeSeconds } from '@/utils/date'
import type { ILeasePayment, ILeaseRecord, IPaymentChannelOption, PaymentChannel } from '@/types/modules/lease'

/**
 * 首期费用支付弹窗（R71/V20）：与续租支付同款渠道流程，但**不含月数选择**——
 * 首期 = 首期租金 + 押金，月数固定 1，金额一律由服务端按租约登记值计算，
 * 客户端不传金额（防篡改口径；本地预览仅供用户预期对齐）。
 *
 * 与 LeaseRenewDialog 的差异（刻意为两个独立组件，避免在 900 行的续租弹窗里
 * 堆分支）：
 * - 无月数选择步骤，仅「选渠道 → 渠道支付 → 轮询确认」；
 * - 支付成功**不改变租期**（协议完成且首期成功后租约生效，R71），故无新止期回读；
 * - 首期只收一次（后端同类型已有「进行中/已完成」单即拒 5904），故**重开弹窗先回查**
 *   既有首期单：命中待支付单 → 以该单渠道续接并直达支付页；命中已支付单 → 明示
 *   已完成并引导到支付记录（DEF-068：此前重开只能停在「选渠道」页，进不去支付页）。
 */

const props = defineProps<{
  /** 目标租约（弹窗打开期间由调用方保证非空；TO_SIGN+SIGNED及旧ACTIVE兼容补缴可发起） */
  lease: ILeaseRecord | null
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 支付成功（支付单状态变化），调用方据此刷新支付记录 */
  success: []
  /** 首期已支付完成时引导查看支付记录（调用方展开该租约卡片定位到支付记录区块） */
  viewPayments: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

/** 轮询节奏：3 秒一次，累计上限 5 分钟（本地无公网回调，以主动查单替代） */
const POLL_INTERVAL_MS = 3000
const POLL_MAX_MS = 5 * 60 * 1000

/** 后端 ErrorCode.PAYMENT_ALREADY_EXISTS（5904）：该类型首期单已完成或进行中 */
const PAYMENT_ALREADY_EXISTS = 5904

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

/* ------------------------------ 流程状态 ------------------------------ */

const step = ref<1 | 2 | 3>(1)
/** 终态视图（success/failed/paid）覆盖三步展示；paid = 打开时回查即已支付 */
const result = ref<'none' | 'success' | 'failed' | 'paid'>('none')
const failReason = ref<'closed' | 'abandoned' | 'timeout' | 'review' | ''>('')

const channel = ref<PaymentChannel | null>(null)
const channelOptions = ref<IPaymentChannelOption[]>([])
const channelsLoading = ref(false)
const submitting = ref(false)
const closing = ref(false)
const payment = ref<ILeasePayment | null>(null)
const alipayBlocked = ref(false)
const qrCanvas = ref<HTMLCanvasElement | null>(null)

/** 打开时回查到的既有首期单：待支付（续接用）/ 已支付（明示用） */
const pendingFirst = ref<ILeasePayment | null>(null)
const paidFirst = ref<ILeasePayment | null>(null)
/** 第 1 步内的回查/续接说明：空串不渲染；只如实说明下一步，不误报待支付或已支付 */
const resumeNotice = ref('')
/** 说明对应「存在待支付单但无法重开支付页」：此时才提供关单重开的兜底动作 */
const resumeBlocked = ref(false)

/** 第 2 步支付凭据是否就绪（支付宝跳转表单 / 微信二维码；列表与查单接口都不返回凭据） */
const credentialMissing = computed(() => {
  const current = payment.value
  if (!current) return false
  return current.channel === 'ALIPAY' ? !current.alipayForm : !current.qrCode
})

/** 首期金额本地预览：月租 + 押金（押金为空按 0 处理；实际金额以服务端返回单据为准） */
const firstQuote=ref<ILeasePayment | null>(null)
const amountPreview = computed(() => firstQuote.value?.amount ?? (props.lease ? props.lease.monthlyRent * (props.lease.firstPaymentMonths ?? 1) + (props.lease.deposit ?? 0) : null))


const failText = computed(() => {
  switch (failReason.value) {
    case 'review':
      return '收到异常付款结果，已保留记录，请联系管理方核对；租约不会自动恢复'
    case 'closed':
      return '支付单已关闭，首期费用未支付'
    case 'abandoned':
      return '已放弃支付，首期费用未支付'
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
      enabled: list.find((opt) => opt.channel === item)?.enabled ?? false,
      configured: list.find((opt) => opt.channel === item)?.configured ?? list.find((opt) => opt.channel === item)?.enabled ?? false
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
  if (!channel.value) {
    ElMessage.warning('请选择支付渠道')
    return
  }
  submitting.value = true
  try {
    /* 金额与月数由服务端计算；同租约同类型已有单由后端按幂等口径处理（返回既有单或 5904） */
    const data = await createFirstPayment(props.lease.id, { channel: channel.value })
    if (data.status === 'SUCCESS') {
      await settleSuccess()
      return
    }
    await enterPaymentStep(data)
  } catch (error) {
    if (isAlreadyExists(error)) {
      await resolveAlreadyExists(pendingFirst.value, error)
      return
    }
    ElMessage.error(error instanceof Error ? error.message : '创建支付单失败')
  } finally {
    submitting.value = false
  }
}

/** 5904「该类型支付单已完成或进行中」：按错误码分流，不能只弹一句错误停在原地 */
function isAlreadyExists(error: unknown): boolean {
  return error instanceof ApiError && error.code === PAYMENT_ALREADY_EXISTS
}

/** 进入第 2 步：按渠道开支付宝新窗口或渲染微信二维码，并开始轮询确认 */
async function enterPaymentStep(data: ILeasePayment): Promise<void> {
  payment.value = data
  step.value = 2
  if (data.channel === 'ALIPAY') {
    if (data.alipayForm) openAlipay(data.alipayForm)
  } else if (data.qrCode) {
    await nextTick()
    await renderQr(data.qrCode)
  }
  startPolling()
}

/**
 * 支付宝整页表单提交（DEF-129）：后端生成的已签名表单 HTML 装入 Blob URL 交给新窗口加载
 * （表单尾部自带 submit 脚本会自动发起跳转），替代 document.write 向新窗口文档流注入
 * 未转义标记；被拦截时置标记供手动重开。
 */
function openAlipay(formHtml: string): void {
  const url = URL.createObjectURL(new Blob([formHtml], { type: 'text/html;charset=utf-8' }))
  const win = window.open(url, '_blank')
  if (!win) {
    /* 拦截场景同步释放，避免 Blob URL 泄漏 */
    URL.revokeObjectURL(url)
    alipayBlocked.value = true
    return
  }
  alipayBlocked.value = false
  /* 加载完成后即释放（表单提交跳转支付宝后该 URL 不再被引用） */
  win.addEventListener('load', () => URL.revokeObjectURL(url))
}

/** 重新打开支付宝支付页：本地有跳转表单直接开；凭据缺失则先向服务端重取 */
async function reopenAlipay(): Promise<void> {
  if (payment.value?.alipayForm) {
    openAlipay(payment.value.alipayForm)
    return
  }
  await refreshCredential()
}

/**
 * 重取支付凭据：列表与查单接口都不返回 alipayForm/qrCode，只有发起接口会带凭据，
 * 故用当前单渠道再走一次发起（后端对待支付单按幂等口径原样返回）。
 */
async function refreshCredential(): Promise<void> {
  if (!props.lease || !payment.value || submitting.value) return
  const existing = payment.value
  submitting.value = true
  try {
    const data = await createFirstPayment(props.lease.id, { channel: existing.channel })
    if (data.status === 'SUCCESS') {
      await settleSuccess()
      return
    }
    await enterPaymentStep(data)
  } catch (error) {
    if (isAlreadyExists(error)) {
      await resolveAlreadyExists(existing, error)
      return
    }
    ElMessage.error(error instanceof Error ? error.message : '重新获取支付页失败')
  } finally {
    submitting.value = false
  }
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
    if(latest.settlementIssue){stopPolling();failReason.value='review';result.value='failed';return}
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
  emit('success')
}

/** 放弃支付：关闭未支付单；关闭被拒（常见于刚支付完成）时立即查单以真实状态收口 */
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

/* ------------------------------ 打开时回查既有首期单（DEF-068） ------------------------------
 * 首期只收一次：后端在存在「进行中/已完成」首期单时不允许建第二张（5904），
 * 故打开弹窗必须先回查——命中待支付单就以该单渠道续接、直达支付页；
 * 命中已支付单就明示已完成并引导支付记录。绝不把用户丢在「选渠道」页干瞪眼。
 */

/** 是否首期单：paymentType 与 payment_type 列一同引入（V20），缺省值只可能是续期单 */
function isFirstPayment(order: ILeasePayment): boolean {
  return order.paymentType === 'FIRST'
}

async function bootstrap(): Promise<void> {
  pendingFirst.value = null
  paidFirst.value = null
  resumeNotice.value = ''
  resumeBlocked.value = false

  const lease = props.lease
  const channels = loadChannels()
  if(props.lease) { try { firstQuote.value=await getRentQuote(props.lease.id,'FIRST') } catch { firstQuote.value=null } }
  if (!lease) {
    await channels
    return
  }

  let orders: ILeasePayment[] | null = null
  try {
    /* 支付单列表与支付渠道互不依赖，并行发起 */
    orders = await listLeasePayments(lease.id)
  } catch {
    /* 回查失败不判定支付状态（既不报待支付也不报已支付），照常让用户选渠道，
       只如实说明「若被拒该去哪看」 */
    resumeNotice.value =
      '未能确认是否已有未支付的首期支付单；若发起被拒，请稍后在「支付记录」中查看。'
  }
  await channels
  if (orders === null) return

  const paid = orders.find((item) => isFirstPayment(item) && item.status === 'SUCCESS')
  if (paid) {
    showPaid(paid)
    return
  }
  const pending = orders.find((item) => isFirstPayment(item) && item.status === 'PENDING')
  if (pending) await resumePending(pending)
}

/** 已支付终态：首期成功后回执金额/时间，父页面回读正式生效状态 */
function showPaid(order: ILeasePayment): void {
  paidFirst.value = order
  payment.value = order
  stopPolling()
  result.value = 'paid'
}

/** 续接既有待支付单：渠道以既有单为准（避免建出第二张单），取回支付凭据后直达第 2 步 */
async function resumePending(order: ILeasePayment): Promise<void> {
  if (!props.lease) return
  pendingFirst.value = order
  channel.value = order.channel
  submitting.value = true
  try {
    const data = await createFirstPayment(props.lease.id, { channel: order.channel })
    if (data.status === 'SUCCESS') {
      await settleSuccess()
      return
    }
    await enterPaymentStep(data)
  } catch (error) {
    if (isAlreadyExists(error)) {
      await resolveAlreadyExists(order, error)
      return
    }
    /* 续接请求本身失败（网络/渠道）：留在第 1 步如实说明，不误判为已支付 */
    resumeNotice.value =
      `续接未支付的首期支付单（${paymentChannelLabels[order.channel]} · ${order.paymentNo}）失败，请稍后重试。`
  } finally {
    submitting.value = false
  }
}

/**
 * 5904 收口：以后端回查结果为准——已支付单存在则明示「已支付完成」并引导支付记录；
 * 仍存在待支付单且无法重开支付页时，说明原因并给出「关闭该单后重新发起」的兜底动作。
 */
async function resolveAlreadyExists(hint: ILeasePayment | null, error: unknown): Promise<void> {
  let orders: ILeasePayment[] | null = null
  try {
    if (props.lease) orders = await listLeasePayments(props.lease.id)
  } catch {
    orders = null
  }
  const paid = orders?.find((item) => isFirstPayment(item) && item.status === 'SUCCESS')
  if (paid) {
    showPaid(paid)
    return
  }
  const pending = orders?.find((item) => isFirstPayment(item) && item.status === 'PENDING') ?? hint
  if (pending) {
    pendingFirst.value = pending
    channel.value = pending.channel
    step.value = 1
    resumeBlocked.value = true
    resumeNotice.value =
      `已存在未支付的首期支付单（${paymentChannelLabels[pending.channel]} · ${pending.paymentNo}），` +
      '当前无法重新打开该支付页；可稍后重试，或关闭该单后重新发起。'
    return
  }
  ElMessage.error(error instanceof Error ? error.message : '创建支付单失败')
}

/** 兜底恢复：关闭既有未支付单后重新回查（后端仅在无进行中/已完成首期单时允许新建） */
async function closeExistingAndRestart(): Promise<void> {
  const target = pendingFirst.value
  if (!target || closing.value) return
  closing.value = true
  try {
    await closePayment(target.paymentNo)
    ElMessage.success('已关闭未支付的首期支付单，可重新发起')
    await bootstrap()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '关闭支付单失败')
  } finally {
    closing.value = false
  }
}

/** 已支付终态的去处：关闭弹窗并让调用方展开该租约卡片的支付记录区块 */
function goPaymentRecords(): void {
  emit('viewPayments')
  visible.value = false
}

/* ------------------------------ 弹窗生命周期 ------------------------------ */

function resetDialog(): void {
  step.value = 1
  result.value = 'none'
  failReason.value = ''
  channel.value = null
  payment.value = null
  alipayBlocked.value = false
}

/** 失败后重新发起：回第 1 步并重新回查（已关单允许重建，故仍走一遍完整流程） */
function retryFromStart(): void {
  result.value = 'none'
  failReason.value = ''
  step.value = 1
  payment.value = null
  alipayBlocked.value = false
  void bootstrap()
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      resetDialog()
      void bootstrap()
    } else {
      /* 关闭仅停轮询：进行中支付单保留，重开时由 bootstrap 回查续接 */
      stopPolling()
    }
  }
)

onUnmounted(stopPolling)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`支付首期费用 · ${lease?.houseLocation ?? ''}`"
    width="480px"
    :close-on-click-modal="false"
  >
    <el-steps v-if="result === 'none'" :active="step - 1" simple class="flow-steps">
      <el-step title="选渠道" />
      <el-step title="支付" />
      <el-step title="确认结果" />
    </el-steps>

    <!-- 第一步：租约摘要 + 金额构成 + 渠道 -->
    <div v-if="result === 'none' && step === 1" v-loading="channelsLoading" class="step-body">
      <dl class="lease-brief">
        <div class="brief-house">
          <dt>房屋</dt>
          <dd>{{ lease?.houseLocation || '—' }}</dd>
        </div>
        <div>
          <dt>月租</dt>
          <dd>{{ money(lease?.monthlyRent) }}</dd>
        </div>
        <div>
          <dt>押金</dt>
          <dd>{{ money(lease?.deposit) }}</dd>
        </div>
      </dl>

      <div class="amount-preview">
        <span class="amount-label">首期费用</span>
        <span class="amount-value">{{ money(amountPreview) }}</span>
        <span class="amount-note">
          = 首期{{ lease?.firstPaymentMonths ?? 1 }}个月租金 {{ money((lease?.monthlyRent ?? 0) * (lease?.firstPaymentMonths ?? 1)) }} + 押金 {{ money(lease?.deposit) }}，最终以支付页为准
        </span>
      </div>

      <!-- 打开回查/续接说明（DEF-068）：有既有单时告知真实状态与下一步，而不是让用户干瞪眼 -->
      <div v-if="resumeNotice" class="resume-notice" :class="{ 'is-blocked': resumeBlocked }">
        <p class="resume-notice-text">{{ resumeNotice }}</p>
        <el-button
          v-if="resumeBlocked"
          type="warning"
          plain
          round
          size="small"
          :loading="closing"
          :disabled="closing"
          @click="closeExistingAndRestart"
        >
          关闭该支付单并重新发起
        </el-button>
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
            <span v-if="!opt.enabled" class="channel-badge">{{ opt.configured ? '未启用' : '未配置' }}</span>
          </button>
        </div>
        <p v-if="!channelsLoading && channelOptions.length === 0" class="channel-empty">
          支付渠道加载失败，请关闭弹窗后重试
        </p>
      </div>

      <p class="pay-tip">
        协议确认且首期费用支付成功后，租约和正式居住关系才生效；首期费用不会顺延租期。
      </p>
    </div>

    <!-- 第二步：渠道支付（支付宝新窗口 / 微信二维码） -->
    <div v-else-if="result === 'none' && step === 2 && payment" class="step-body">
      <p class="pay-meta-line">
        <span class="mono">单号 {{ payment.paymentNo }}</span>
        <span>{{ paymentChannelLabels[payment.channel] }}</span>
        <span>{{ money(payment.amount) }}</span>
        <span>首期费用</span>
      </p>

      <!-- 续接说明（DEF-068）：渠道以既有待支付单为准，避免用户以为按自己的新选择建了单 -->
      <p
        v-if="pendingFirst && pendingFirst.paymentNo === payment.paymentNo"
        class="resume-line"
      >
        已续接您此前未支付的首期支付单，渠道以该单为准（{{ paymentChannelLabels[payment.channel] }}）
      </p>

      <p v-if="payment.channelExpiresAt || payment.expiresAt" class="pay-note">
        <span v-if="payment.channelExpiresAt">付款截止：{{ formatDateTimeSeconds(payment.channelExpiresAt) }}</span><br />
        <span v-if="payment.expiresAt">系统订单到期：{{ formatDateTimeSeconds(payment.expiresAt) }}</span>
      </p>

      <template v-if="payment.channel === 'ALIPAY'">
        <div v-if="payment.alipayForm && !alipayBlocked" class="pay-guide">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" />
            <polyline points="15 3 21 3 21 9" />
            <line x1="10" y1="14" x2="21" y2="3" />
          </svg>
          已在新窗口打开支付宝，请在支付宝页面完成付款
        </div>
        <div v-else-if="payment.alipayForm" class="pay-blocked">
          浏览器拦截了弹出新窗口，请点击下方「重新打开支付页」前往支付宝
        </div>
        <!-- 续接既有单时后端可能只回单不回跳转表单：明示可重取，不留一个点了没反应的按钮 -->
        <div v-else class="pay-blocked">
          支付页凭据暂未就绪，请点击下方「重新获取支付页」重试
        </div>
      </template>
      <template v-else>
        <div v-if="payment.qrCode" class="qr-area">
          <canvas ref="qrCanvas" class="qr-canvas" width="220" height="220"></canvas>
          <p class="qr-hint">请使用微信扫码支付</p>
        </div>
        <div v-else class="pay-blocked">
          二维码暂未就绪，请点击下方「重新获取支付页」重试，或放弃后重新发起
        </div>
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

    <!-- 终态：成功（首期支付不改租期，故只回执单号与金额） -->
    <div v-else-if="result === 'success'" class="step-body">
      <div class="result-box is-success">
        <span class="result-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20 6 9 17l-5-5" />
          </svg>
        </span>
        <p class="result-title">首期费用支付成功</p>
        <p class="result-sub">
          金额 <strong>{{ money(payment?.amount) }}</strong>（首期租金 + 押金），租约状态不变
        </p>
        <p v-if="payment" class="result-sub mono">支付单号 {{ payment.paymentNo }}</p>
      </div>
    </div>

    <!-- 终态：打开时回查即已支付（或 5904 复核命中成功单）；不改租约状态，引导到支付记录 -->
    <div v-else-if="result === 'paid'" class="step-body">
      <div class="result-box is-success">
        <span class="result-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
            <path d="M20 6 9 17l-5-5" />
          </svg>
        </span>
        <p class="result-title">首期费用已支付完成</p>
        <p class="result-sub">
          金额 <strong>{{ money((paidFirst ?? payment)?.amount) }}</strong>（首期租金 + 押金），租约状态不变
        </p>
        <p v-if="(paidFirst ?? payment)?.paidAt" class="result-sub">
          支付时间 {{ formatDateTime((paidFirst ?? payment)?.paidAt) }}
        </p>
        <p v-if="paidFirst ?? payment" class="result-sub mono">
          支付单号 {{ (paidFirst ?? payment)?.paymentNo }}
        </p>
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
        <template v-else-if="result === 'paid'">
          <el-button round @click="visible = false">关闭</el-button>
          <el-button type="primary" round @click="goPaymentRecords">查看支付记录</el-button>
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
            v-if="credentialMissing"
            type="warning"
            plain
            round
            :loading="submitting"
            :disabled="submitting"
            @click="refreshCredential"
          >
            重新获取支付页
          </el-button>
          <el-button
            v-else-if="payment?.channel === 'ALIPAY' && alipayBlocked"
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
  min-height: 240px;
}

.mono {
  font-family: var(--font-family-mono);
}

/* 租约摘要：房屋占满整行，月租/押金并排 */
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

.field {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.field-label {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* 金额预览条：主色金额 + 构成说明 */
.amount-preview {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--spacing-xs) var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
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
  color: var(--color-text-secondary);
}

/* 渠道卡片：双列，选中主色描边，未配置置灰并挂角标 */
.channel-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--spacing-sm);
}

.channel-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.channel-card.is-selected {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
}

.channel-card.is-disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.channel-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-sm);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

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
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.channel-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.channel-badge {
  flex-shrink: 0;
  white-space: nowrap;
  align-self: center;
  margin-left: auto;
  padding: 1px var(--spacing-xs);
  border-radius: var(--radius-pill);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: 11px;
}

.channel-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}

.pay-tip {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
}

/* 回查/续接说明（DEF-068）：提示为主，无法续接时才附「关单重开」兜底按钮 */
.resume-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg-hover);
}

.resume-notice.is-blocked {
  background: var(--color-warning-soft);
}

.resume-notice-text {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.resume-notice.is-blocked .resume-notice-text {
  color: var(--color-warning);
}

/* 支付步骤：单号/渠道/金额一行摘要 */
.pay-meta-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.pay-guide,
.pay-blocked {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
}

/* 续接既有单时的渠道来源说明 */
.resume-line {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.pay-guide {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.pay-guide svg {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

.pay-blocked {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.qr-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
}

.qr-canvas {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.qr-hint {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.pay-note {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

/* 轮询等待 */
.poll-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xl) 0;
}

.poll-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: var(--radius-circle);
  animation: poll-spin 0.9s linear infinite;
}

@keyframes poll-spin {
  to {
    transform: rotate(360deg);
  }
}

.poll-title {
  margin: var(--spacing-sm) 0 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.poll-sub {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 终态盒：成功绿 / 失败红 */
.result-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-xl) var(--spacing-md);
  border-radius: var(--radius-lg);
  text-align: center;
}

.result-box.is-success {
  background: var(--color-success-soft);
}

.result-box.is-failed {
  background: var(--color-danger-soft);
}

.result-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-circle);
  color: #fff;
}

.result-box.is-success .result-icon {
  background: var(--color-success);
}

.result-box.is-failed .result-icon {
  background: var(--color-danger);
}

.result-icon svg {
  width: 22px;
  height: 22px;
}

.result-title {
  margin: var(--spacing-sm) 0 0;
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
  gap: var(--spacing-sm);
}
@media (max-width: 560px) { .channel-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
