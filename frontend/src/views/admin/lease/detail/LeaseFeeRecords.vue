<script setup lang="ts">
import type { IDepositRefund, ILeasePayment } from '@/types/modules/lease'
import { depositRefundStatusLabels, leasePaymentTypeLabels } from '@/types/modules/lease'
import StatusTag from '@/components/common/StatusTag.vue'
import { formatDateTime } from '@/utils/date'
import { moneyText } from './display'
defineProps<{ depositRefunds: IDepositRefund[]; payments: ILeasePayment[]; canRefundDeposit: boolean; refundDisabledReason: string; loading: boolean; error: string }>()
const emit = defineEmits<{ refund: []; retry: [] }>()
function depositRefundTone(status: IDepositRefund['status']): 'completed' | 'pending' | 'rejected' { return status === 'SUCCESS' ? 'completed' : status === 'PENDING' ? 'pending' : 'rejected' }
const paymentLabels = { PENDING: '待支付', SUCCESS: '支付成功', CLOSED: '已关闭' }
</script>
<template>
 <div class="lease-fee-sections">
  <p v-if="loading" role="status">费用记录加载中…</p><p v-if="error" class="section-error" role="alert">{{ error }} <el-button text type="primary" @click="emit('retry')">重试费用记录</el-button></p>
  <article class="panel"><h2 class="panel-title">支付记录 <span class="title-note">仅展示已保存记录，不主动查单</span></h2>
   <ul v-if="payments.length" class="payment-list"><li v-for="item in payments"  :key="item.paymentNo"><header><strong>{{ item.paymentType ? leasePaymentTypeLabels[item.paymentType] : '历史付款' }}</strong><span>{{ paymentLabels[item.status] }}</span><b>{{ moneyText(item.amount) }}</b></header><p>{{ item.paymentNo }} · {{ item.channel === 'ALIPAY' ? '支付宝' : '微信支付' }} · {{ item.months }}个月</p><p v-if="item.periodStart">覆盖 {{ item.periodStart }} ～ {{ item.periodEnd }}</p><p>发起 {{ formatDateTime(item.createdAt) }}<template v-if="item.paidAt"> · 支付 {{ formatDateTime(item.paidAt) }}</template></p><p v-if="item.settlementIssue" class="section-error">结算提示：{{ item.settlementIssue }}</p></li></ul>
   <el-empty v-else-if="!loading && !error" description="暂无支付记录" :image-size="60" />
  </article>
<article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 3.2 20 7v6c0 4.2-3.2 7-8 7.8-4.8-.8-8-3.6-8-7.8V7z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M12 8.4v4.2M12 15.4h.01" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              押金与退还
              <span class="title-note">共 {{ depositRefunds.length }} 条退还流水</span>
              <el-button
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                class="title-action"
                type="primary"
                plain
                size="small"
                :disabled="!canRefundDeposit || loading || !!error"
                :title="refundDisabledReason || '按实缴渠道原路退还'"
                @click="emit('refund')"
              >
                发起押金退还
              </el-button>
            </h2>

            <!-- 不可发起时把原因写出来（禁用按钮本身不解释原因） -->
            <p v-if="refundDisabledReason" class="refund-disabled-note">{{ refundDisabledReason }}</p>

            <ul v-if="depositRefunds.length > 0" class="refund-list">
              <li v-for="item in depositRefunds" :key="item.id" class="refund-item">
                <span class="refund-amount">{{ moneyText(item.amount) }}</span>
                <span class="refund-channel">{{ item.channel === 'ALIPAY' ? '支付宝' : '微信支付' }}</span>
                <StatusTag
                  :label="depositRefundStatusLabels[item.status]"
                  :type="depositRefundTone(item.status)"
                />
                <span class="refund-meta">
                  发起 {{ formatDateTime(item.createdAt) }} ·
                  完成 {{ item.refundedAt ? formatDateTime(item.refundedAt) : '—' }}
                </span>
                <p class="refund-meta mono">请求号 {{ item.outRequestNo }}</p>
                <p v-if="item.failReason" class="refund-fail">失败原因：{{ item.failReason }}</p>
              </li>
            </ul>
            <el-empty v-else-if="!loading && !error" description="尚无押金退还流水" :image-size="70" />
          </article>
 </div>
</template>
<style scoped>.payment-list{list-style:none;padding:0;margin:0}.payment-list li{padding:16px 0;border-bottom:1px solid var(--color-border);overflow-wrap:anywhere}.payment-list header{display:flex;gap:14px;align-items:center;flex-wrap:wrap}.payment-list b{margin-left:auto}.payment-list p{font-size:12px;color:var(--color-text-secondary);line-height:1.6}.payment-list header span{font-size:12px;color:var(--color-primary)}.section-error{color:var(--color-danger,#b24f36);font-size:13px}</style>
