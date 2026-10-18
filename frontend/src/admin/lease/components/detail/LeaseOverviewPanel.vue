<script setup lang="ts">
import { computed } from 'vue'
import type { ILeaseRecord } from '@/common/lease/types/lease'
import { depositStatusLabels } from '@/common/lease/types/lease'
import { formatDate, formatDateTime } from '@/common/web/utils/date'
import { leaseNo, moneyText } from '@/admin/lease/utils/detail/display'
const props = defineProps<{ lease: ILeaseRecord; editable: boolean; editDisabledReason: string; remainText: string; remainTone: string }>()
const emit = defineEmits<{ edit: [] }>()
const depositStatus = computed(() => props.lease.depositStatus ?? 'UNPAID')
const depositLabel = computed(() => depositStatusLabels[depositStatus.value])
</script>
<template><article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M5 4.5h14v15H5z" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M8.5 9h7M8.5 12.5h7M8.5 16h4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              租约属性
              <span v-if="!editable" class="title-note">{{ editDisabledReason }}</span>
              <el-button
                v-else
                v-permission="['ADMIN', 'SUPER_ADMIN']"
                class="title-action"
                type="primary"
                plain
                size="small"
                @click="emit('edit')"
              >
                编辑属性
              </el-button>
            </h2>

            <span class="block-label">登记信息</span>
            <dl class="info-rows">
              <div class="info-row">
                <dt>租约号</dt>
                <dd>{{ leaseNo(lease.id) }}（ID {{ lease.id }}）</dd>
              </div>
              <div class="info-row">
                <dt>租户</dt>
                <dd>{{ lease.tenantName || '—' }}（居民 ID {{ lease.tenantId }}）</dd>
              </div>
              <div class="info-row">
                <dt>房屋</dt>
                <dd>{{ lease.houseLocation || '—' }}（房屋 ID {{ lease.houseId }}）</dd>
              </div>
              <div class="info-row">
                <dt>社区</dt>
                <dd>{{ lease.communityName || `社区 ID ${lease.communityId}` }}</dd>
              </div>
              <div class="info-row">
                <dt>登记时间</dt>
                <dd>{{ formatDateTime(lease.createdAt) }}</dd>
              </div>

            </dl>

            <p class="payment-arrangement">付款安排：合同 {{ lease.contractMonths ?? '未记录' }} 个月 · 首付 {{ lease.firstPaymentMonths ?? '未记录' }} 个月 · 后续建议周期 {{ lease.paymentCycleMonths ?? '未记录' }} 个月</p>
            <span class="block-label">
              可编辑属性
              <em class="label-hint">（变更后自动留痕，字段级前后值）</em>
            </span>
            <dl class="info-rows">
              <div class="info-row">
                <dt>租期</dt>
                <dd>
                  {{ formatDate(lease.startDate) }} ~ {{ formatDate(lease.endDate) }}
                  <span v-if="remainText" class="remain-chip inline" :class="remainTone">{{ remainText }}</span>
                </dd>
              </div>
              <div class="info-row">
                <dt>月租金</dt>
                <dd class="money-text">{{ moneyText(lease.monthlyRent) }}</dd>
              </div>
              <div class="info-row">
                <dt>押金</dt>
                <dd class="money-text">{{ moneyText(lease.deposit) }}</dd>
              </div>
              <!-- 押金状态与退还（R72）：状态 + 累计已退 + 抵扣说明 + 发起入口 -->
              <div class="info-row">
                <dt>押金状态</dt>
                <dd class="deposit-cell">
                  <span class="deposit-pill" :data-deposit="depositStatus">{{ depositLabel }}</span>

                  <template v-if="lease.depositOffsetRemark">
                    <span class="deposit-meta">抵扣说明：{{ lease.depositOffsetRemark }}</span>
                  </template>
                </dd>
              </div>
              <div class="info-row">
                <dt>合同附件</dt>
                <dd>
                  <a
                    v-if="lease.contractUrl"
                    class="file-link"
                    :href="lease.contractUrl"
                    target="_blank"
                    rel="noopener"
                  >
                    查看合同附件
                  </a>
                  <span v-else class="muted-text">未上传</span>
                </dd>
              </div>
              <div class="info-row">
                <dt>备注</dt>
                <dd>{{ lease.remark || '—' }}</dd>
              </div>
            </dl>
          </article></template>
