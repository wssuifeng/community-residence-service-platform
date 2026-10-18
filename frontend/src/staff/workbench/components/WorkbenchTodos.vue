<script setup lang="ts">
import StatusTag from '@/common/web/components/StatusTag.vue'
import { workOrderStatusLabels, workOrderPriorityLabels, type IWorkOrder, type WorkOrderStatus } from '@/common/workorder/types/workorder'
import {formatRelative} from '@/common/web/utils/date'
import { residentDisplayName as displayName, residentInitial as initialOf, residentRelationLabel as relationLabel } from '@/common/resident/utils/staffResidentPresentation'
defineProps<{orders:IWorkOrder[];count:number}>()
const emit=defineEmits<{all:[];detail:[order:IWorkOrder]}>()
const statusSemantic: Record<WorkOrderStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  PENDING: 'pending',
  TO_ASSIGN: 'pending',
  TO_CONFIRM: 'pending',
  ASSIGNED: 'processing',
  ACCEPTED: 'processing',
  IN_PROGRESS: 'processing',
  COMPLETED: 'completed',
  CLOSED: 'completed',
  REJECTED: 'rejected',
  CANCELLED: 'canceled'
}

</script>
<template>
      <section class="panel todo-panel">
        <header class="panel-header">
          <h3 class="panel-title">待办工单</h3>
          <button class="panel-more" type="button" @click="emit('all')">
            全部
            <em v-if="count > 0">{{ count }}</em>
          </button>
        </header>

        <el-empty v-if="orders.length === 0" description="暂无待办工单" :image-size="80" />

        <ul v-else class="todo-list">
          <li
            v-for="order in orders"
            :key="order.id"
            class="todo-row"
            :class="{ 'is-urgent': order.priority==='URGENT' }"
            @click="emit('detail',order)"
          >
            <span class="todo-flag">{{ workOrderPriorityLabels[order.priority] }}</span>

            <div class="todo-body">
              <div class="todo-main">
                <div class="todo-title-line">
                  <p class="todo-title">{{ order.title }}</p>
                  <span class="todo-meta">{{ order.orderNo }} · {{ formatRelative(order.createdAt) }}</span>
                </div>

                <div class="todo-person">
                  <span class="todo-avatar" aria-hidden="true">{{ initialOf(order.residentName) }}</span>
                  <span class="todo-person-text">
                    <span class="todo-name" :title="order.residentName">{{ displayName(order) }}</span>
                    <span class="todo-identity">{{ relationLabel(order) }}</span>
                  </span>
                </div>
              </div>

              <StatusTag :label="workOrderStatusLabels[order.status]" :type="statusSemantic[order.status]" />

              <button class="todo-action" type="button" @click.stop="emit('detail',order)">
                {{ order.status === 'ASSIGNED' ? '接单' : '继续处理' }}
              </button>
            </div>
          </li>
        </ul>
      </section>

</template>
