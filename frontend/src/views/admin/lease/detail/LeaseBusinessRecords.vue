<script setup lang="ts">
import type { ILeaseChange } from '@/types/modules/lease'
import { leaseChangeTypeLabels } from '@/types/modules/lease'
import type { IViewingAppointment } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import StatusTag from '@/components/common/StatusTag.vue'
import { formatDate, formatDateTime } from '@/utils/date'
import { CHANGE_TONE, changeDescription, valueText, operatorText } from './display'
defineProps<{ changes: ILeaseChange[]; viewings: IViewingAppointment[]; changesError: string; viewingsError: string; loading: boolean }>()
const emit = defineEmits<{ retryChanges: []; retryViewings: [] }>()
</script>
<template><div class="lease-record-sections"><p v-if="loading" role="status">业务记录加载中…</p><article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="4" y="5.5" width="16" height="15" rx="2" stroke="currentColor" stroke-width="1.8" />
                <path d="M4 10.2h16M8.5 3.2v4M15.5 3.2v4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              关联看房（R69）
              <span class="title-note">共 {{ viewings.length }} 条 · 会话内发起的看房记录</span>
            </h2>

            <ul v-if="viewings.length > 0" class="viewing-list">
              <li v-for="item in viewings" :key="item.id" class="viewing-item">
                <div class="viewing-main">
                  <span class="viewing-title">{{ item.housingTitle || `房源 #${item.housingId}` }}</span>
                  <span class="viewing-time mono">
                    {{ formatDate(item.appointmentDate) }} {{ item.startTime.slice(0, 5) }} ~ {{ item.endTime.slice(0, 5) }}
                  </span>
                </div>
                <div class="viewing-side">
                  <span class="viewing-assignee">
                    带看人 {{ item.assigneeName || '未分配' }}
                  </span>
                  <StatusTag
                    :label="viewingAppointmentStatusLabels[item.status]"
                    :type="item.status === 'COMPLETED' ? 'completed' : item.status === 'CANCELLED' || item.status === 'VIOLATED' ? 'canceled' : 'pending'"
                  />
                </div>
              </li>
            </ul>
            <p v-if="viewingsError" role="alert">{{ viewingsError }} <button type="button" @click="emit('retryViewings')">重试</button></p>
            <el-empty v-else-if="!loading" description="该租约暂无关联看房记录" :image-size="70" />
          </article>
<article class="panel">
            <h2 class="panel-title">
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <circle cx="12" cy="12" r="8.5" stroke="currentColor" stroke-width="1.8" />
                <path d="M12 7.5V12l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
              变更历史
              <span class="title-note">最新在前 · 共 {{ changes.length }} 条</span>
            </h2>

            <ol v-if="changes.length > 0" class="timeline">
              <li v-for="change in changes" :key="change.id" class="tl-item">
                <span class="tl-marker" aria-hidden="true">
                  <span class="tl-dot" :class="CHANGE_TONE[change.changeType]" />
                </span>
                <div class="tl-body">
                  <p class="tl-head">
                    <span class="change-tag" :class="CHANGE_TONE[change.changeType]">
                      {{ leaseChangeTypeLabels[change.changeType] }}
                    </span>
                    <span class="tl-field">{{ changeDescription(change) }}</span>
                    <span class="tl-time">{{ formatDateTime(change.createdAt) }}</span>
                  </p>
                  <p v-if="change.fieldLabel" class="tl-diff">
                    <span class="diff-old">{{ valueText(change, change.oldValue) }}</span>
                    <span class="diff-arrow">→</span>
                    <span class="diff-new">{{ valueText(change, change.newValue) }}</span>
                  </p>
                  <p v-else-if="change.newValue" class="tl-note">{{ change.newValue }}</p>
                  <p class="tl-operator">
                    操作人 {{ operatorText(change) }}
                    <template v-if="change.reason">
                      <span class="meta-sep">·</span>
                      说明：{{ change.reason }}
                    </template>
                  </p>
                </div>
              </li>
            </ol>
            <el-empty v-else-if="!changesError && !loading" description="尚无变更记录" :image-size="70" />
            <!-- 变更历史为辅助信息：取不到只提示，不影响详情主体（原来会连带整页空白） -->
            <p v-if="changesError" class="changes-error">
              变更历史暂不可用：{{ changesError }}
              <el-button text type="primary" size="small" @click="emit('retryChanges')">重试</el-button>
            </p>
          </article></div></template>
